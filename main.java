package liseda.matcha.main;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Scanner;

import org.apache.commons.lang3.StringUtils;
import org.dom4j.DocumentException;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.formats.ManchesterSyntaxDocumentFormat;
import org.semanticweb.owlapi.model.AxiomType;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.MissingImportHandlingStrategy;
import org.semanticweb.owlapi.model.OWLAnnotationProperty;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyCreationException;
import org.semanticweb.owlapi.model.OWLOntologyLoaderConfiguration;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.model.OWLOntologyStorageException;
import org.semanticweb.owlapi.search.EntitySearcher;

import liseda.matcha.alignment.Alignment;
import liseda.matcha.alignment.Mapping;
import liseda.matcha.io.alignment.rdf.AlignmentIORDF;
import liseda.matcha.io.ontology.OWLAPIConnector;
import liseda.matcha.io.ontology.OntologyReader;
import liseda.matcha.ontology.Ontology;
import liseda.matcha.ontology.ReferenceMap;
import liseda.matcha.ontology.lexicon.Lexicon;
import liseda.matcha.semantics.EntityType;
import liseda.matcha.semantics.SemanticMap;

public class PromptPipeline {

	private static DateTimeFormatter dtf = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss");
	private static Random rand = new Random();

	private static ReferenceMap rm;
	private static SemanticMap sm;
	private static Lexicon lex;

	private static HashMap<String, HashSet<String>> family_ties = new HashMap<String, HashSet<String>>();

	private static HashMap<String,String> LDs; // all LDs IRIs
	private static HashMap<String,String> examples; // LD IRIs that work as examples
	private static HashMap<String,String> not_examples; // remaining LDs
	private static HashSet<String> all_namespaces; // all ontology namespaces (with _)

	private static String main_folder;
	private static int test_size; // nr of LDs to test (run prompts, calculate GED)

	public static void main(String[] args) throws OWLOntologyCreationException, OWLOntologyStorageException, IOException, DocumentException {

		System.out.println(dtf.format(LocalDateTime.now()) + "  | Running prompt pipeline");

		String CMOM_path = args[0];
		String ontology_path = args[1];
		main_folder = args[2];
		test_size = Integer.parseInt(args[3]);
		String ontologies_folder = args[4];
		String splits = args[5];

		Ontology o = OntologyReader.parseInputOntology(ontology_path);
		rm = o.getReferenceMap();
		lex = o.getLexicon(EntityType.CLASS);
		new File(main_folder).mkdirs();

		// support files
		printSupportFiles(ontologies_folder);

		// populate data structures
		getLDs(o);
		getExamplesFromLDs(o);
		getAllNamespaces();
		getNotExamples(splits);
		getNotExamples();

		// mappings for manual evaluation
		String nonLD_path = "./llm_1to1.rdf";
		Alignment nonLD_alignment = new Alignment();
		AlignmentIORDF.read(nonLD_alignment, nonLD_path);
		String save_path = main_folder + "manual-evaluation/";
		getMappingsForManualEvaluation_TOP(50, nonLD_alignment, save_path, ontology_path, o);
		createManualEvaluationDoc(o, "./owl_doc.tsv", "./manual-evaluation/");

		// CMOM
		Alignment cmom = new Alignment();
		AlignmentIORDF.read(cmom, CMOM_path);

		splitRemaining(700, cmom);
		runLDtestset(ontology_path, o, cmom);

	}

	private static void splitRemaining(int max, Alignment cmom) throws IOException {
		PrintWriter out = new PrintWriter(new FileOutputStream("./WBP_1000-list.txt"));
		int i = 0;
		for(String ld : LDs.keySet()) {
			String ref = LDs.get(ld);
			if(!examples.containsKey(ld) && filterExamples(ld, ref).size() > 0 && cmom.containsSource(ld)) {
				out.println(ld);
				out.flush();
				i++;
			}
			if(i == max) {
				return;
			}
		}
		out.close();
	}

	private static void createManualEvaluationDoc(Ontology o, String save_path, String folder_path) throws IOException, OWLOntologyCreationException {
		PrintWriter out = new PrintWriter(new FileOutputStream(save_path));
		out.println("Class\tLabels\tDefinition\tChatGPT mapping\tFidelity\tNotes");
		Lexicon lex = o.getLexicon(EntityType.CLASS);
		for(File folder : new File(folder_path).listFiles()) {
			if(!folder.getAbsolutePath().contains("doc") && !folder.getAbsolutePath().contains("rdf")) {
				String code = StringUtils.substringAfterLast(folder.getAbsolutePath(), "/");
				System.out.println(" --- " + code);
				File file = new File(folder_path + code + "/" + code + "_answer_few-shot.owl");
				String iri = "http://purl.obolibrary.org/obo/" + code;
				String labels = String.join(", ", lex.getNames(iri));
				String def = getDefinition(o, iri);
	
				String mapping = "";
				Scanner scanner = new Scanner(file);
				while (scanner.hasNextLine()) {
					String next = scanner.nextLine();
					mapping = mapping + next;
				}
				
				String part = "<owl:Class ";
				String map = "    " + part + StringUtils.substringBetween(mapping, part, "</owl:equivalentClass>") + "</owl:equivalentClass>  </owl:Class>";
				
				out.println(code + "\t" + labels + "\t" + def + "\t" + "" + "\t\t");
				
				out.flush();
				scanner.close();
			}
		}
		out.close();
	}

	private static String getDefinition(Ontology onto, String iri) throws OWLOntologyCreationException {
		OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
		OWLOntologyLoaderConfiguration conf = new OWLOntologyLoaderConfiguration().setMissingImportHandlingStrategy(MissingImportHandlingStrategy.SILENT);
		manager.setOntologyLoaderConfiguration(conf);
		OWLOntology o = manager.loadOntologyFromOntologyDocument(new File(onto.getLocation()));

		OWLAPIConnector oac = new OWLAPIConnector();
		OWLDataFactory factory = oac.getFactory();

		// prop IAO_0000115
		HashSet<String> temp = new HashSet<String>();
		OWLAnnotationProperty prop = factory.getOWLAnnotationProperty(IRI.create("http://purl.obolibrary.org/obo/IAO_0000115"));
		EntitySearcher.getAnnotationObjects(factory.getOWLClass(IRI.create(iri)), o).forEach(a -> {
			if(a.containsEntityInSignature(prop)) {
				String def = a.annotationValue().toString().replace("\"", "").replace("^^xsd:string", "");
				temp.add(def);
			}
		});
		return String.join(", ", temp);
	}

	private static void getMappingsForManualEvaluation_TOP(int nr, Alignment a, String save_path, String o_path, Ontology o) throws IOException, OWLOntologyCreationException, OWLOntologyStorageException, DocumentException {
		System.out.println(dtf.format(LocalDateTime.now()) + "  | Constructing " + nr + " mappings for manual evaluation");
		System.out.println("Alignment has " + a.size() + " mappings");

		// check if mappings have examples after filtering
		Alignment b = new Alignment();
		for(Mapping m : a) {
			if(filterExamples(m.getEntity1(), m.getEntity2()).size() > 0) {
				b.add(m);
			}
		}
		System.out.println("Found " + b.size() + " mappings with examples");
		// sort remaining mappings by similarity
		b.sortDescending();

		HashSet<String> list = map_check();
		System.out.println(list.size());
		HashSet<String> done = new HashSet<String>();

		Alignment c = new Alignment();
		String mod = "manual-evaluation/";
		int i = 0;
		for(Mapping m : b) {
			System.out.println(i + ") " + m.getEntity2());
			if(i == 6) {
				break;
			}
			if(!list.contains(m.getEntity2()) && !done.contains(m.getEntity2())) {
				done.add(m.getEntity2());
				c.add(m);
				constructArgumentsAndRunOurPrompts(mod, m.getEntity2(), StringUtils.substringAfterLast(m.getEntity2(), "/"), lex.getBestName(m.getEntity2()), m.getEntity1(), o_path, o);
				i++;
			}
		}
		System.out.println("Saved " + c.size() + " mappings");
		AlignmentIORDF.save(c, save_path + "mappings4manual-evaluation.rdf");
	}

	private static HashSet<String> map_check() {
		HashSet<String> maps = new HashSet<String>();
		maps.add("http://purl.obolibrary.org/obo/HP_0002680"); // 1
		maps.add("http://purl.obolibrary.org/obo/HP_0005048"); // 2
		maps.add("http://purl.obolibrary.org/obo/HP_0011807"); // 3
		maps.add("http://purl.obolibrary.org/obo/HP_0011157"); // 4
		maps.add("http://purl.obolibrary.org/obo/HP_0025495"); // 5
		maps.add("http://purl.obolibrary.org/obo/HP_0030045"); // 6
		maps.add("http://purl.obolibrary.org/obo/HP_0030310"); // 7
		maps.add("http://purl.obolibrary.org/obo/HP_0400001"); // 8
		maps.add("http://purl.obolibrary.org/obo/HP_0030322"); // 9 
		maps.add("http://purl.obolibrary.org/obo/HP_0012041"); // 10
		maps.add("http://purl.obolibrary.org/obo/HP_0011829"); // 11
		maps.add("http://purl.obolibrary.org/obo/HP_0040116"); // 12
		maps.add("http://purl.obolibrary.org/obo/HP_0001057"); // 13
		maps.add("http://purl.obolibrary.org/obo/HP_0008345"); // 14
		maps.add("http://purl.obolibrary.org/obo/HP_0033977"); // 15
		maps.add("http://purl.obolibrary.org/obo/HP_0001088"); // 16
		maps.add("http://purl.obolibrary.org/obo/HP_0006595"); // 17
		maps.add("http://purl.obolibrary.org/obo/HP_0003554"); // 18
		maps.add("http://purl.obolibrary.org/obo/HP_0032106"); // 19
		maps.add("http://purl.obolibrary.org/obo/HP_0004383"); // 20
		maps.add("http://purl.obolibrary.org/obo/HP_0008822"); // 21
		maps.add("http://purl.obolibrary.org/obo/HP_0009752"); // 22
		maps.add("http://purl.obolibrary.org/obo/HP_0011087"); // 23
		maps.add("http://purl.obolibrary.org/obo/HP_4000062"); // 24
		maps.add("http://purl.obolibrary.org/obo/HP_0006375"); // 25
		maps.add("http://purl.obolibrary.org/obo/HP_0001852"); // 26
		maps.add("http://purl.obolibrary.org/obo/HP_0031260"); // 27
		maps.add("http://purl.obolibrary.org/obo/HP_0100307"); // 28
		maps.add("http://purl.obolibrary.org/obo/HP_0011120"); // 29
		maps.add("http://purl.obolibrary.org/obo/HP_0025362"); // 30
		maps.add("http://purl.obolibrary.org/obo/HP_0005528"); // 31
		maps.add("http://purl.obolibrary.org/obo/HP_0040118"); // 32
		maps.add("http://purl.obolibrary.org/obo/HP_0012074"); // 33
		maps.add("http://purl.obolibrary.org/obo/HP_0008954"); // 34
		maps.add("http://purl.obolibrary.org/obo/HP_0004308"); // 35
		maps.add("http://purl.obolibrary.org/obo/HP_0100265"); // 36
		maps.add("http://purl.obolibrary.org/obo/HP_0011525"); // 37
		maps.add("http://purl.obolibrary.org/obo/HP_0033328"); // 38
		maps.add("http://purl.obolibrary.org/obo/HP_0030303"); // 39
		maps.add("http://purl.obolibrary.org/obo/HP_0100272"); // 40
		maps.add("http://purl.obolibrary.org/obo/HP_0001944"); // 41
		maps.add("http://purl.obolibrary.org/obo/HP_0000752"); // 42
		maps.add("http://purl.obolibrary.org/obo/HP_0011335"); // 43
		maps.add("http://purl.obolibrary.org/obo/HP_0006329"); // 44
		return maps;
	}

	private static void getMappingsForManualEvaluation(int nr, Alignment a, String save_path, String o_path, Ontology o) throws OWLOntologyStorageException, OWLOntologyCreationException, IOException, DocumentException {
		System.out.println(dtf.format(LocalDateTime.now()) + "  | Constructing " + nr + " mappings for manual evaluation");
		System.out.println("Alignment has " + a.size() + " mappings");

		Alignment b = new Alignment();
		for(Mapping m : a) {
			if(filterExamples(m.getEntity1(), m.getEntity2()).size() > 0) {
				b.add(m);
			}
		}
		System.out.println("Found " + b.size() + " mappings with examples");

		// randomise
		HashSet<Integer> indexes = new HashSet<Integer>();
		for(int i = 0; i < nr; i++) {
			int random = rand.nextInt((b.size() - 0) + 1) + 0;
			if(indexes.contains(random)) {
				random = rand.nextInt((b.size() - 0) + 1) + 0;
				indexes.add(random);
			}
			else {
				indexes.add(random);
			}
		}
		Alignment c = new Alignment();
		for(int i : indexes) {
			c.add(b.get(i));
		}

		String mod = "manual-evaluation/";
		// each mapping
		int i = 0;
		for(Mapping m : c) {
			System.out.println(i + ") " + m.getEntity1());
			constructArgumentsAndRunOurPrompts(mod, m.getEntity1(), StringUtils.substringAfterLast(m.getEntity1(), "/"), lex.getBestName(m.getEntity1()), m.getEntity2(), o_path, o);
			convert2Manchester(save_path, save_path + StringUtils.substringAfterLast(m.getEntity1(), "/") + "/" + StringUtils.substringAfterLast(m.getEntity1(), "/") + "_answer_few-shot.owl");
			i++;
		}
		System.out.println("Saved " + c.size() + " mappings");
		AlignmentIORDF.save(c, save_path + "mappings4manual-evaluation.rdf");
	}

	private static void convert2Manchester(String folder_path, String file_path) throws OWLOntologyStorageException, OWLOntologyCreationException {
		OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
		OWLOntology o = manager.loadOntologyFromOntologyDocument(new File(file_path));
		manager.saveOntology(o, new ManchesterSyntaxDocumentFormat(), IRI.create(new File(file_path.replace(".owl", "_man.owl"))));
	}

	private static void runLDtestset(String path, Ontology o, Alignment CMOM) throws OWLOntologyCreationException, OWLOntologyStorageException, DocumentException, IOException {
		System.out.println(dtf.format(LocalDateTime.now()) + "  | Running loop for each test LD until they total " + test_size);
		System.out.println("(create folder, save original LD, get original classes, filter examples, run prompts, calculate GED)");
		int i = 0;
		// for each test LD (get prompt inputs, filter examples, run python (prompts+GED))
		for(String iri : not_examples.keySet()) {
			System.out.println(dtf.format(LocalDateTime.now()) + "  | " + i + ") " + iri);

			String code = StringUtils.substringAfterLast(iri, "/");
			String name = lex.getBestName(iri);
			String ref = not_examples.get(iri);
			String CMOM_ent2 = CMOM.getSourceMappings(iri).get(0).getEntity2();

			constructArgumentsAndRunPrompts("LD-test-set/", iri, code, name, ref, path, o, false);
			constructArgumentsAndRunPrompts("CMOM-test-set/", iri, code, name, CMOM_ent2, path, o, true);

			i++;
			if(i == test_size) {
				System.out.println(dtf.format(LocalDateTime.now()) + "  | Finished!");
				return;
			}
		}
	}

	private static void saveReference(String iri, String mod, String code, String path, Ontology o) throws OWLOntologyCreationException, OWLOntologyStorageException, FileNotFoundException {
		HashSet<String> trueLD = new HashSet<String>();
		trueLD.add(iri);
		String filename = main_folder + mod + code + "/" + code + "_trueLD.owl";
		printClassAxiomsToOntology(path, filename, trueLD);
		addLabelsAsComments(o, filename); 
	}

	private static void constructArgumentsAndRunOurPrompts(String mod, String iri, String code, String name, String ref, String path, Ontology o) throws OWLOntologyCreationException, OWLOntologyStorageException, IOException, DocumentException {
		new File(main_folder + mod + code).mkdirs(); // create folder
		saveReference(iri, mod, code, path, o); // save true LD as reference in folder
		// construct argument for classes
		String arg_classes = getCMOMclasses(ref, code);
		// construct argument for examples
		HashSet<String> filtered_examples = filterExamples(iri, ref);
		String filename = main_folder + mod + code + "/" + code + "_examples.owl";
		printClassAxiomsToOntology(path, filename, filtered_examples);
		addLabelsAsComments(o, filename);
		filename = filename.replace(".owl", "_4humans.owl");
		String arg_examples = convertOWL2String(filtered_examples, filename);
		// run python (src_iri, src_name, classes, examples based on namespace and cardinality)
		runJustOurPrompts(iri, name, arg_classes, arg_examples, main_folder + mod);
	}

	private static void constructArgumentsAndRunPrompts(String mod, String iri, String code, String name, String ref, String path, Ontology o, boolean run_zero) throws OWLOntologyCreationException, OWLOntologyStorageException, IOException, DocumentException {
		new File(main_folder + mod + code).mkdirs(); // create folder
		saveReference(iri, mod, code, path, o); // save true LD as reference in folder
		// construct argument for classes
		String arg_classes = "";
		if(ref.contains(">"))
			arg_classes = getLDclasses(ref);
		else
			arg_classes = getCMOMclasses(ref, code);
		// construct argument for examples
		HashSet<String> filtered_examples = filterExamples(iri, ref);
		String filename = main_folder + mod + code + "/" + code + "_examples.owl";
		printClassAxiomsToOntology(path, filename, filtered_examples);
		addLabelsAsComments(o, filename);
		filename = filename.replace(".owl", "_4humans.owl");
		String arg_examples = convertOWL2String(filtered_examples, filename);
		// run python (src_iri, src_name, classes, examples based on namespace and cardinality)
		runPrompts(iri, name, arg_classes, arg_examples, main_folder + mod, run_zero);
	}

	private static void printSupportFiles(String ontologies_folder) throws OWLOntologyCreationException, IOException {
		new File(main_folder + "control-files/").mkdirs();
		List<Ontology> ontos = loadAllOntologies(ontologies_folder);
		sm = SemanticMap.getInstance();
		for(Ontology on : ontos) {
			printIRIsAndLabels(on);
			ontologySizes(on);
			findFamilyTies(on);
		}
		printFamilyTies();
	}

	private static String getCMOMclasses(String ref, String code) throws DocumentException, IOException {
		new File(main_folder + "CMOM-test-set/" + code).mkdirs();
		PrintWriter out = new PrintWriter(new FileOutputStream(main_folder + "CMOM-test-set/" + code + "/" + code + "_CMOMclasses.txt"));
		HashSet<String> parts = new HashSet<String>();
		for(String part : ref.split(", ")) {
			part = part.replace("AND[", "").replace("]", "");
			out.println(part);
			parts.add(part + " (" + lex.getBestName(part) + ")");
		}
		out.close();
		return String.join(", ", parts);
	}

	private static HashSet<String> getExamples() {
		HashSet<String> examples = new HashSet<String>();
		examples.add("http://purl.obolibrary.org/obo/HP_0002810"); // correct
		examples.add("http://purl.obolibrary.org/obo/HP_0011756"); // correct*
		examples.add("http://purl.obolibrary.org/obo/HP_0007628"); // correct*
		examples.add("http://purl.obolibrary.org/obo/HP_0007605"); // contains
		examples.add("http://purl.obolibrary.org/obo/HP_0010230"); // contains
		examples.add("http://purl.obolibrary.org/obo/HP_0003370"); // contains*
		examples.add("http://purl.obolibrary.org/obo/HP_0003987"); // contains*
		examples.add("http://purl.obolibrary.org/obo/HP_0031377"); // contained
		examples.add("http://purl.obolibrary.org/obo/HP_0003901"); // contained
		examples.add("http://purl.obolibrary.org/obo/HP_0011876"); // contained*
		examples.add("http://purl.obolibrary.org/obo/HP_0012819"); // contained*
		examples.add("http://purl.obolibrary.org/obo/HP_0010448"); // overlap
		examples.add("http://purl.obolibrary.org/obo/HP_0030082"); // overlap
		examples.add("http://purl.obolibrary.org/obo/HP_0006339"); // overlap*
		examples.add("http://purl.obolibrary.org/obo/HP_0030870"); // overlap*
		return examples;
	}

	private static void printFamilyTies() throws IOException {
		FileWriter fw = new FileWriter(main_folder + "control-files/family-ties.tsv", true);
		BufferedWriter bw = new BufferedWriter(fw);
		PrintWriter out = new PrintWriter(bw);
		for(String iri : family_ties.keySet()) {
			HashSet<String> family = family_ties.get(iri);
			out.println(iri + "\t" + String.join(",", family));
			out.flush();
		}
		out.close();
	}

	private static void findFamilyTies(Ontology o) throws IOException {
		for(String ent : o.getEntities(EntityType.CLASS)) {
			HashSet<String> family = new HashSet<String>();
			// getSubclasses(String uri, int distance, boolean includeExpressions)
			for(String c : sm.getSubclasses(ent, 1, false)) {
				family.add(c);
			}
			// getSuperclasses(String uri, int distance, boolean includeExpressions)
			for(String p : sm.getSuperclasses(ent, 1, false)) {
				family.add(p);
			}
			if(family_ties.containsKey(ent)) {
				family.addAll(family_ties.get(ent));
				family_ties.replace(ent, family);
			}
			else {
				family_ties.put(ent, family);
			}
		}
	}

	private static void ontologySizes(Ontology o) throws IOException {
		FileWriter fw = new FileWriter(main_folder + "control-files/onto-sizes.tsv", true);
		BufferedWriter bw = new BufferedWriter(fw);
		PrintWriter out = new PrintWriter(bw);
		String ns = StringUtils.substringAfterLast(StringUtils.substringBeforeLast(o.getURI(), "."), "/");
		out.println(ns + "\t" + o.size());
		out.close();
	}

	private static List<Ontology> loadAllOntologies(String folder) throws OWLOntologyCreationException {
		System.out.println(dtf.format(LocalDateTime.now()) + "  | Loading all ontologies");
		List<Ontology> ontos = new ArrayList<Ontology>();
		for(File f : new File(folder).listFiles()) {
			if(f.isFile())
				ontos.add(OntologyReader.parseInputOntology(f.getAbsolutePath()));
		}
		System.out.println(dtf.format(LocalDateTime.now()) + "  | Finished!");
		return ontos;
	}

	private static List<Ontology> loadAllOntologies() throws OWLOntologyCreationException {
		System.out.println(dtf.format(LocalDateTime.now()) + "  | Loading all ontologies");
		List<Ontology> ontos = new ArrayList<Ontology>();
		ontos.add(OntologyReader.parseInputOntology("./hp.owl"));
		ontos.add(OntologyReader.parseInputOntology("./mp.owl"));
		ontos.add(OntologyReader.parseInputOntology("./wbphenotype.owl"));

		ontos.add(OntologyReader.parseInputOntology("./bfo.owl"));
		ontos.add(OntologyReader.parseInputOntology("./ro.owl"));

		ontos.add(OntologyReader.parseInputOntology("./chebi.owl"));
		ontos.add(OntologyReader.parseInputOntology("./cl.owl"));
		ontos.add(OntologyReader.parseInputOntology("./go.owl"));
		ontos.add(OntologyReader.parseInputOntology("./pato.owl"));
		ontos.add(OntologyReader.parseInputOntology("./uberon.owl"));
		ontos.add(OntologyReader.parseInputOntology("./wbbt.owl"));
		System.out.println(dtf.format(LocalDateTime.now()) + "  | Finished!");
		return ontos;
	}

	private static void printIRIsAndLabels(Ontology o) throws IOException {
		FileWriter fw = new FileWriter(main_folder + "control-files/IRIlabels.tsv", true);
		BufferedWriter bw = new BufferedWriter(fw);
		PrintWriter out = new PrintWriter(bw);

		Lexicon lex = o.getLexicon(EntityType.CLASS);
		for(String iri : o.getEntities(EntityType.CLASS)) {
			out.println(iri + "\t" + lex.getBestName(iri));
			out.flush();
		}
		lex = o.getLexicon(EntityType.OBJECT_PROP);
		for(String iri : o.getEntities(EntityType.OBJECT_PROP)) {
			out.println(iri + "\t" + lex.getBestName(iri));
			out.flush();
		}
		lex = o.getLexicon(EntityType.DATA_PROP);
		for(String iri : o.getEntities(EntityType.DATA_PROP)) {
			out.println(iri + "\t" + lex.getBestName(iri));
			out.flush();
		}
		out.close();
	}

	private static void runJustOurPrompts(String iri, String name, String classes, String examples, String folder) throws IOException {
		String[] arg = new String[] {"python","./run_prompts_just-ours.py", iri, name, classes, examples, folder};
		Process p = Runtime.getRuntime().exec(arg);
		String line;
		BufferedReader error = new BufferedReader(new InputStreamReader(p.getErrorStream()));
		while((line = error.readLine()) != null) {
			System.out.println(line);
		}
	}

	private static void runPrompts(String iri, String name, String classes, String examples, String folder, boolean run_zero) throws IOException {
		String[] arg = new String[] {"python3", "./run_prompts.py", iri, name, classes, examples, folder, String.valueOf(run_zero)};
		Process p = Runtime.getRuntime().exec(arg);
		String line;
		BufferedReader error = new BufferedReader(new InputStreamReader(p.getErrorStream()));
		while((line = error.readLine()) != null) {
			System.out.println(line);
		}
	}

	private static void runEvaluation() throws IOException {
		String[] arg = new String[] {"python3", main_folder + "evaluate.py"};
		Process p = Runtime.getRuntime().exec(arg);
		String line;
		BufferedReader error = new BufferedReader(new InputStreamReader(p.getErrorStream()));
		while((line = error.readLine()) != null) {
			System.out.println(line);
		}
	}

	private static String convertOWL2String(HashSet<String> filtered_examples, String filename) throws FileNotFoundException {
		String arg_examples = "";
		String all_lines = "";

		Scanner scanner = new Scanner(new File(filename));
		while (scanner.hasNextLine()) {
			String next = scanner.nextLine();
			all_lines = all_lines + next;
		}

		String preamble = "<?xml version=\"1.0\"?>" + StringUtils.substringBetween(all_lines, "<?xml version=\"1.0\"?>", "<owl:Ontology rdf:about=\"") + "<owl:Ontology rdf:about=\"" + filename + "\"/>";

		arg_examples = preamble;

		for(String ex : filtered_examples) {
			String comment = "<!-- " + ex + " -->";
			arg_examples = arg_examples + "    " + comment + StringUtils.substringBetween(all_lines, comment, "</owl:equivalentClass>") + "</owl:equivalentClass>  </owl:Class>";
		}
		return arg_examples;
	}

	private static String getLDclasses(String ref) {
		HashSet<String> classes = new HashSet<String>();
		for(String part : StringUtils.substringsBetween(ref, "<", ">")) {
			if(!part.contains("RO_") && !part.contains("BFO_"))
				classes.add(part + " (" + lex.getBestName(part) + ")");
		}
		return String.join(", ", classes);
	}

	private static HashSet<String> filterExamples(String iri, String ref) throws IOException {
		FileWriter fw = new FileWriter(main_folder + "control-files/example-counts.tsv", true);
		BufferedWriter bw = new BufferedWriter(fw);
		PrintWriter out = new PrintWriter(bw);

		HashSet<String> filtered_examples = new HashSet<String>();
		List<String> namespaces = getNamespaces(ref); // get list of namespaces
		for(String ld : examples.keySet()) {
			String ref_ex = examples.get(ld);
			List<String> ns_ex = getNamespaces(ref_ex);
			if(ns_ex.containsAll(namespaces)) {
				filtered_examples.add(ld);
			}
			//			if(card == card_ex && namespaces.equals(ns_ex)) {
			//				filtered_examples.add(ld);
			//			}
		}
		out.println(iri + "\t" + filtered_examples.size());
		out.close();
		return filtered_examples;
	}

	// count entities in LD
	private static int getCardinality(String ref, HashSet<String> namespaces) {
		int i = 0;
		for(String ns : namespaces) {
			i = i + StringUtils.countMatches(ref, ns);
		}
		return i;
	}

	// get ontology namespaces in LD
	private static List<String> getNamespaces(String ref) {
		List<String> namespaces = new ArrayList<String>();
		for(String ns : all_namespaces) {
			for(int i = 0; i < StringUtils.countMatches(ref,ns); i++) {
				namespaces.add(ns);
			}
		}
		if(ref.contains("PATO_0000460")) {
			namespaces.remove("PATO_");
		}
		Collections.sort(namespaces);
		return namespaces;
	}

	// get all namespaces in all LDs
	private static void getAllNamespaces() {
		all_namespaces = new HashSet<String>();
		for(String ld : LDs.keySet()) {
			String ref = LDs.get(ld);
			for(String part : StringUtils.substringsBetween(ref, "<", ">")) {
				String ns = StringUtils.substringAfterLast(StringUtils.substringBeforeLast(part, "_"), "/") + "_";
				if(!all_namespaces.contains(ns))
					all_namespaces.add(ns);
			}
		}
		all_namespaces.remove("RO_");
		all_namespaces.remove("BFO_");
		System.out.println(dtf.format(LocalDateTime.now()) + "  | Found " + all_namespaces.size() + " distinct namespaces: " + all_namespaces);
	}

	// get one example of each namespace pattern in all LDs
	private static void getExamplesFromLDs(Ontology o) throws FileNotFoundException {
		PrintWriter out = new PrintWriter(new FileOutputStream(main_folder + "control-files/examples.tsv"));
		examples = new HashMap<String,String>();
		HashSet<String> patterns = new HashSet<String>();

		for(String ld : LDs.keySet()) {
			String ref = LDs.get(ld);
			String edit = ref.strip();
			for(String part : StringUtils.substringsBetween(ref, "_", ">")) { // iterate through IRIs inside LD
				edit = edit.replace(part, ""); // remove code from IRIs in LD expression
			}
			if(!patterns.contains(edit)) {
				patterns.add(edit);
				examples.put(ld, ref);
				out.println(ld  +"\t" + ref);
				out.flush();
			}
		}
		System.out.println(dtf.format(LocalDateTime.now()) + "  | Found " + examples.keySet().size() + " namespace pattern examples");
		out.close();
	}

	private static void getNotExamples(String splits) throws FileNotFoundException {
		not_examples = new HashMap<String, String>();
		Scanner scanner = new Scanner(new File(splits));
		while (scanner.hasNextLine()) {
			String iri = scanner.nextLine();
			String ref = LDs.get(iri);
			not_examples.put(iri, ref);
		}
		System.out.println(dtf.format(LocalDateTime.now()) + "  | " + not_examples.keySet().size() + " test LDs");
	}

	private static void getNotExamples() {
		not_examples = LDs;
		for(String iri : examples.keySet()) {
			not_examples.remove(iri);
		}
		not_examples.remove("http://purl.obolibrary.org/obo/HP_0005093"); // CMOM alignment uses a class not found in HP
		System.out.println(dtf.format(LocalDateTime.now()) + "  | Left with " + not_examples.keySet().size() + " possible test LDs");
	}

	// get all LDs (filter LDs that include NBO, MPATH, BSPO, or PR)
	private static void getLDs(Ontology o) throws FileNotFoundException {
		PrintWriter out = new PrintWriter(new FileOutputStream(main_folder + "control-files/LDs.tsv"));
		LDs = new HashMap<String,String>();
		for(String iri : rm.getEntities()) {
			for(String ref : rm.getReferences(iri)) {
				if(!ref.contains("NBO_") && !ref.contains("MPATH_") && !ref.contains("BSPO_") && !ref.contains("PR_") && !ref.contains("union") && !ref.contains("HP_") && !ref.contains("MP_") && !ref.contains("mpath#part_") && !ref.contains("wbphenotype-equivalent-axioms-subq#in_response_") && !ref.contains("WBls_") && !ref.contains("CARO_") & !ref.contains("wbphenotype-equivalent-axioms-subq#during") && !ref.contains("wbphenotype-equivalent-axioms-subq#ends_during_or_")&& !ref.contains("wbphenotype-equivalent-axioms-subq#has_")&& !ref.contains("wbphenotype-equivalent-axioms-subq#starts_during_or_")) {
					if(ref.contains(">")) {
						LDs.put(iri, ref);
						out.println(iri + "\t" + ref);
						out.flush();
					}
				}
			}
		}
		System.out.println(dtf.format(LocalDateTime.now()) + "  | Found " + LDs.keySet().size() + " logical definitions in " + o.getURI());
		out.close();
	}

	private static void addLabelsAsComments(Ontology o, String ontology_path) throws FileNotFoundException {		
		String out_file = ontology_path.replace(".owl", "_4humans.owl");
		PrintWriter out = new PrintWriter(new FileOutputStream(out_file));

		Lexicon clex = o.getLexicon(EntityType.CLASS);
		Lexicon plex = o.getLexicon(EntityType.OBJECT_PROP);

		// read each line of the ontology file, add label as a comment, and write to new file
		Scanner scanner = new Scanner(new File(ontology_path));
		while (scanner.hasNextLine()) {
			String next = scanner.nextLine();
			if(next.contains("rdf:resource") || next.contains("rdf:about")) {
				String uri = StringUtils.substringBefore(StringUtils.substringAfter(next, "=\""), "\"/>").replace("\">", "");
				if(!clex.getBestName(uri).isEmpty()) {
					String new_line = next + " <!-- " + clex.getBestName(uri) + " -->";
					out.println(new_line);
					out.flush();
				}
				else if(!plex.getBestName(uri).isEmpty()) {
					String new_line = next + " <!-- " + plex.getBestName(uri) + " -->";
					out.println(new_line);
					out.flush();
				}
				else {
					out.println(next);
					out.flush();
				}
			}
			else {
				out.println(next);
				out.flush();
			}
		}
		out.close();
	}

	private static void printClassAxiomsToOntology(String ontology_path, String new_ontology, HashSet<String> classList) throws OWLOntologyCreationException, OWLOntologyStorageException {
		OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
		OWLOntologyLoaderConfiguration conf = new OWLOntologyLoaderConfiguration().setMissingImportHandlingStrategy(MissingImportHandlingStrategy.SILENT);
		manager.setOntologyLoaderConfiguration(conf);
		OWLOntology o1 = manager.loadOntologyFromOntologyDocument(new File(ontology_path));
		OWLOntology o_out = manager.createOntology(IRI.create(new_ontology));

		OWLAPIConnector con = new OWLAPIConnector();
		con.openOntology(ontology_path);

		for(OWLClass c : con.getClasses()) {
			if(classList.contains(c.getIRI().toURI().toString())) {
				o1.getAxioms(c, true).forEach(ax -> {
					if(!ax.isOfType(AxiomType.SUBCLASS_OF))
						o_out.addAxioms(ax);
				});
			}
		}
		manager.saveOntology(o_out, manager.getOntologyFormat(o1), IRI.create(new File(new_ontology)));
	}
}

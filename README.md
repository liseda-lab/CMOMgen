# CMOMgen

CMOMgen is the first end-to-end approach to generate semantically sound and complete complex multi-ontology mappings without any restrictions on the number of target ontologies or entities. It uses In-Context Learning enhanced by Retrieval-Augmented Generation that selects relevant classes and filters reference examples.
We also provide an evaluation metric based on graph edit distance that can evaluate complex mappings in precision, recall, and F-measure.

The following files are shared:
* main.java: Runs the entire pipeline
* run_prompts.py: Prompts chatGPT using its API
* evaluate.py: Evaluates the resulting constructs using our graph edit ditance-based metrics
* reference alignments for three tasks based on three different biomedical ontologies (also shared in IEEE DataPort with DOI [10.21227/btb7-yd20](https://dx.doi.org/10.21227/btb7-yd20))

The matching tasks used were:
- Human Phenotype Ontology (HP) vs. Cell Ontology (CL), Chemical Entities of Biological Interest (ChEBI), Gene Ontology (GO), Phenotype and Trait Ontology (PATO), Uber Anatomy Ontology (UBERON)
- Mammalian Phenotype Ontology (MP) vs. Cell Ontology (CL), Chemical Entities of Biological Interest (ChEBI), Gene Ontology (GO), Phenotype and Trait Ontology (PATO), Uber Anatomy Ontology (UBERON)
- Worm Phenotype Ontology (WBP) vs. Chemical Entities of Biological Interest (ChEBI), Gene Ontology (GO), Phenotype and Trait Ontology (PATO), _C.elegans_ Gross Anatomy Ontology (WBbt)

### Methodology
<p align="center">
<img src="./figures/CMOMgen_workflow.png" data-canonical-src="./figures/CMOMgen_workflow.png" width="900" />
</p>


**Input:** CMOMgen takes as input one source ontology and any number of target ontologies.

**Class selection**: based on [CMOM-RS](https://github.com/liseda-lab/CMOM-RS), combines a lexical approach and a language model approach to find relevant classes to construct the complex mappings

**Pattern extraction**: Filters reference complex mappings to match the selected classes in namespace and cardinality

**Mapping composition**: Prompts a language model using the selected classes and the examples to provide a finalized computer-readable complex mapping.

### Reference
Silva, M. C., Faria, D., & Pesquita, C. (2026). CMOMgen: Complex multi-ontology alignment via pattern-guided in-context learning. Journal of Web Semantics, 100884.

```
@article{silva2026cmomgen,
  title={CMOMgen: Complex Multi-Ontology Alignment via Pattern-Guided In-Context Learning},
  author={Silva, Marta Contreiras and Faria, Daniel and Pesquita, Catia},
  journal={Journal of Web Semantics},
  pages={100884},
  year={2026},
  publisher={Elsevier}
}

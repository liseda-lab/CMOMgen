#!/usr/bin/env python
# coding: utf-8

In[1]:


#system
import os
import sys
# graph
import networkx as nx
# xml
import xml.etree.ElementTree as ET
import itertools
# other
import random


In[2]:


base_folder = ""


In[3]:


## save labels
labels_file = open(base_folder + "control-files/IRIlabels.tsv",'r')
labels = {}
for line in labels_file:
    iri = line.split("\t")[0].strip()
    label = line.split("\t")[1].strip()
    labels[iri] = label


In[4]:


## save ontology sizes
sizes_file = open(base_folder + "control-files/onto-sizes.tsv",'r')
sizes = {}
total = 0
for line in sizes_file:
    namespace = line.split("\t")[0].strip().lower()
    size = int(line.split("\t")[1].strip())
    sizes[namespace] = size
    total = total + size
sizes["all"] = total


In[5]:


## save parent-child relations
rel_file = open(base_folder + "control-files/family-ties.tsv",'r')
rel = {}
for line in rel_file:
    iri = line.split("\t")[0].strip()
    family = line.split("\t")[1].strip() # list of all related entities divided by ,
    rel[iri] = family


In[6]:


def find_all_intersections(e):
    if e.findall(".//{*}intersectionOf") is not None:
        return e.findall(".//{*}intersectionOf")
    else:
        return None
def find_all_onproperty(e):
    if e.findall(".//{*}onProperty") is not None:
        return e.findall(".//{*}onProperty")
    else:
        return None
def find_next_onproperty(e):
    if e.find(".//{*}onProperty") is not None:
        return e.find(".//{*}onProperty")
    else:
        return None
def find_all_somevalues(e):
    if e.findall(".//{*}someValuesFrom") is not None:
        return e.findall(".//{*}someValuesFrom")
    elif e.findall(".//{*}hasValue") is not None:
        return e.findall(".//{*}hasValue")
    else:
        return None
def find_next_somevalues(e):
    if e.find(".//{*}someValuesFrom") is not None:
        return e.find(".//{*}someValuesFrom")
    elif e.find(".//{*}hasValue") is not None:
        return e.find(".//{*}hasValue")
    else:
        return None
def find_all_restrictions(e):
    if e.findall(".//{*}Restriction") is not None:
        return e.findall(".//{*}Restriction")
    else:
        return None
def find_all_equivalentClasses(e):
    if e.findall(".//{*}equivalentClass") is not None:
        return e.findall(".//{*}equivalentClass")
    else:
        return None
def find_all_subClasses(e):
    if e.findall(".//{*}subClassOf") is not None:
        return e.findall(".//{*}subClassOf")
    else:
        return None
def find_all_unions(e):
    if e.findall(".//{*}unionOf") is not None:
        return e.findall(".//{*}unionOf")
    else:
        return None

def return_attribute_iri(attrib):
    if attrib is None:
        return ""
    elif attrib.get("{http://www.w3.org/1999/02/22-rdf-syntax-ns#}Resource") is not None:
        return attrib.get("{http://www.w3.org/1999/02/22-rdf-syntax-ns#}Resource")
    elif attrib.get("{http://www.w3.org/1999/02/22-rdf-syntax-ns#}resource") is not None:
        return attrib.get("{http://www.w3.org/1999/02/22-rdf-syntax-ns#}resource")
    elif attrib.get("{http://www.w3.org/1999/02/22-rdf-syntax-ns#}about") is not None:
        return attrib.get("{http://www.w3.org/1999/02/22-rdf-syntax-ns#}about")
    # 
    elif attrib.get("{http://www.w3.org/1999/XMLSchema-instance}about") is not None:
        return attrib.get("{http://www.w3.org/1999/XMLSchema-instance}about")
    else:
        return ""


In[7]:


## union, intersection, restriction
def parse_union(u, prev):
    for e in u:
        c = return_attribute_iri(e)
        links.append(prev + "\t" + c + "\n")
    
def parse_intersection(i, prev):
    if prev == "":
        j = random.randrange(300)
        prev = "bnode_" + str(j)
    
    descrip = ""
    if len(find_all_restrictions(i)) == 0:
        for d in i:
            if "}Class" in d.tag:
                for e in d:
                    if "intersectionOf" in e.tag:
                        parse_intersection(e,descrip)
                    elif "unionOf" in e.tag:
                        parse_union(e, descrip)
                    descrip = return_attribute_iri(e.attrib)
                    links.append(prev + "\t" + descrip + "\n")
            elif "intersectionOf" in d.tag:
                parse_intersection(d,descrip)
            elif "unionOf" in d.tag:
                parse_union(d, descrip)
            descrip = return_attribute_iri(d.attrib)
            links.append(prev + "\t" + descrip + "\n")
    else:
        for r in i:
            if "Description" in r.tag:
                descrip = return_attribute_iri(i.find(".//{*}Description"))
                links.append(prev + "\t" + descrip + "\n")
            elif "Class" in r.tag:
                descrip = return_attribute_iri(i.find(".//{*}Class"))
                links.append(prev + "\t" + descrip + "\n")
            elif "Resource" in r.tag:
                descrip = return_attribute_iri(i.find(".//{*}Resource"))
                links.append(prev + "\t" + descrip + "\n")
            elif "Restriction" in r.tag:
                onprop = return_attribute_iri(find_next_onproperty(r))
                someval = return_attribute_iri(find_next_somevalues(r))
                if someval == "":
                    links.append(prev + "\t" + onprop + "\n")
                    i = r.find(".//{*}intersectionOf")
                    u = r.find(".//{*}unionOf")
                    if i is not None:
                        parse_intersection(i, onprop)
                    elif u is not None:
                        parse_union(u, onprop)
                else:
                    parse_restriction(r, prev)

def parse_restriction(r, prev):
    onprop = return_attribute_iri(find_next_onproperty(r))
    
    if prev == "":
        prev = onprop
    
    i = r.find(".//{*}intersectionOf")
    u = r.find(".//{*}unionOf")
    
    if i is not None:
        parse_intersection(i, onprop)
    elif u is not None:
        parse_union(u, onprop)
    else:
        someval = return_attribute_iri(find_next_somevalues(r))
        if prev != onprop:
            links.append(prev + "\t" + onprop + "\n")
        links.append(onprop + "\t" + someval + "\n")


In[8]:


## parse chatgpt answer and create graph
def parse_answer(filename, name):
    parse_xml(filename, name)
    print_links(links, filename)
    return create_graph(filename)

def parse_xml(filename, name):
    global links
    links = []
    
    try:
        ns = filename.split("/")[-1].split("_")[0] + "_"
        tree = ET.parse(filename)
        
        main_class = ""
        for c in tree.findall(".//{*}Class"):
            if return_attribute_iri(c.attrib) != "" and ns in return_attribute_iri(c.attrib):
                main_class = return_attribute_iri(c.attrib)
                break
        
        if name not in main_class:
            print(filename)
            links.append("[INFO - FP] " + main_class + " != " + name)
        else:
            eq_count = len(find_all_equivalentClasses(tree))
            sc_count = len(find_all_subClasses(tree))
            u_count = len(find_all_unions(tree))
            i_count = len(find_all_intersections(tree))
            r_count = len(find_all_restrictions(tree))
            
            if eq_count > 0: # needs at least one equivalentClass statement to be a true positive
                links.append("[INFO - TP] subClassOf: " + str(sc_count) + ", equivalentClass: " + str(eq_count) + ", restrictions: " + str(r_count) + ", intersectionOf: " + str(i_count) + ", unionOf: " + str(u_count) + "\n")
                
                for eq in find_all_equivalentClasses(tree):
                    for e in eq:
                        if "}Class" in e.tag:
                            for e2 in e:
                                if "Restriction" in e2.tag:
                                    parse_restriction(e2, "")
                                elif "intersectionOf" in e2.tag:
                                    parse_intersection(e2, "")
                                elif "unionOf" in e2.tag:
                                    parse_union(e2, "")
                        elif "Restriction" in e.tag:
                            parse_restriction(e, "")
                        elif "intersectionOf" in e.tag:
                            parse_intersection(e, "")
                        elif "unionOf" in e.tag:
                            parse_union(e, "")
                            
            else: # otherwise it's a false negative
                links.append("[INFO - FN] subClassOf: " + str(sc_count) + ", equivalentClass: " + str(eq_count) + ", restrictions: " + str(r_count) + ", intersectionOf: " + str(i_count) + ", unionOf: " + str(u_count) + "\n")
            
    except ET.ParseError as e:
        print(filename)
        links.append("[INFO] parse error: " + str(e) + "\n")
    
def create_graph(filename):
    links = open(filename.replace(".owl", "_links.tsv"),'r')
    G = nx.Graph()
    
    for line in links:
        if "INFO" in line:
            if "parse error" in line or "FN" in line or "FP" in line:
                print("line: ", line)
                return None
            else:
                continue
        else:
            e1 = line.split("\t")[0].strip()
            e2 = line.split("\t")[1].strip()
            if e1 != "" and e2 != "":
                # save namespaces
                n1 = e1.split("/")[-1].split("_")[0].lower()
                n2 = e2.split("/")[-1].split("_")[0].lower()
                
                G.add_node(e1, iri=e1, namespace=n1)
                G.add_node(e2, iri=e2, namespace=n2)
                G.add_edge(e1, e2)
            elif e1 == "" and e2 != "":
                n2 = e2.split("/")[-1].split("_")[0].lower()
                G.add_node(e2, iri=e2, namespace=n2)
    return G

def create_CMOM_graph(classes):
    G = nx.Graph()
    
    for iri in classes:
        if "PATO_0000460" not in iri:
            ns = iri.split("/")[-1].split("_")[0].lower()
            G.add_node(iri, iri=iri, namespace=ns)
        
    return G


In[9]:


def print_links(links, filename):
    links_file = open(filename.replace(".owl", "_links.tsv"),'w')
    clean_links = []
    pato_links = []
    pato_card = 0
    flag = False
    
    # PATO_0000460 exists?
    for link in links:
         if "PATO_0000460" in link:
            flag = True
            pato_links.append(link)
            pato_card = pato_card+1
    
    if flag: # PATO_0000460 exists
        i = 0
        for pato in pato_links:
            e1 = pato.split("\t")[0].strip()
            for link in links:
                if "INFO" not in link:
                    e2 = link.split("\t")[1].strip()
                    if e2 == e1 and i < pato_card:
                        i = i+1
                        continue
                    elif "PATO_0000460" in link:
                        continue
                    else:
                        clean_links.append(link)
                else:
                    clean_links.append(link)
        for link in clean_links:
            links_file.write(link)
            links_file.flush()
    else: # PATO_0000460 does not exist
        for link in links:
            links_file.write(link)
            links_file.flush()                
    
    links_file.close()


In[10]:


## functions for GED   
def node_subst_cost(n1,n2):
    if n1['iri'] == n2['iri']: # verify if it's the same iri (same iri = same entity)
        return 0
    elif n1['iri'] in rel and n2['iri'] in rel[n1['iri']]:
        calc = (family.count(",")+1)/sizes["all"]
        return calc # aproximate count with ,s
    else:
        return 1
    
def node_del_cost(n1,n2):
    return 0.5 # based on yes/no (probabilistic definition)
    
def edge_cost(n1):
    return 0.5 # based on yes/no (probabilistic definition)


In[11]:


## calculate GED and score
def get_info(filename):
    file = open(filename.replace(".owl", "_links.tsv"),'r')
    return file.readline()

def calc_GED(g0, g1):
    ged = nx.graph_edit_distance(g0, g1, node_subst_cost=node_subst_cost, edge_del_cost=edge_cost, edge_ins_cost=edge_cost)
    return ged
    
def calc_score(ged, LD, mapping):
    score = 1-(ged/max_edits(LD,mapping))
    return score

def max_edits(LD,mapping):
    edges = (0.5*max(LD.number_of_edges(), mapping.number_of_edges()))
    nodes = (1*LD.number_of_nodes())
    diff = mapping.number_of_nodes()-LD.number_of_nodes()
    if diff > 0:
        nodes = nodes + (0.5*diff)
    return edges+nodes


In[12]:


def evaluate(folder, path_part): 
    global TP
    TP = 0
    global FP
    FP = 0
    global FN
    FN = 0
    total_score = 0
    test_size = len(os.listdir(folder))

    for code in os.listdir(folder):
        LD = parse_answer(test_folder + code + "/" + code + "_trueLD.owl", code) # graph for LD
        filename = test_folder + code + "/" + code + path_part
        score = evaluate_each(filename, LD, code)
        total_score = total_score + score
        
    prf(path_part.replace("_answer_", "").replace(".owl",""), TP, FP, FN, test_size, total_score)
    
def evaluate_each(filename, LD_graph, code):
    graph = parse_answer(filename, code)
    if graph is not None:
        global TP
        TP = TP + 1
        info = get_info(filename).strip()
        ged = calc_GED(LD_graph,graph)
        score = calc_score(ged, LD_graph, graph)
        indiv.write(filename.split("/")[-1].replace(".owl","") + "\t" + str(ged) + "\t" + str(score) + "\t" + info + "\n")
        indiv.flush()
        return score
    else:
        print(code, ", ", graph)
        global FP
        info = get_info(filename).strip()
        if "FP" in info:
            FP = FP + 1
        elif "FN" in info:
            global FN
            FN = FN + 1
        elif "TP" in info: # has equivalentClass but it's empty (no links, no graph)
            FP = FP + 1
        indiv.write(filename.split("/")[-1].replace(".owl","") + "\t\t\t" + info + "\n")
        indiv.flush()
            
        return 0
    
def evaluate_CMOM(code, LD_graph, graph):
    global TP
    TP = TP + 1
    ged = calc_GED(LD_graph,graph)
    score = calc_score(ged, LD_graph, graph)
    indiv.write(code + "_justCMOM" + "\t" + str(ged) + "\t" + str(score) + "\n")
    indiv.flush()
    return score
            
def prf(name, TP, FP, FN, size, score):
    print("--- ", name)
    precision = score/(TP+FP)
    recall = score/(size)
    if precision+recall > 0:
        fmeasure = 2*((precision*recall)/(precision+recall))
    else:
        fmeasure = 0
    print("p: ", precision)
    print("r: ", recall)
    print("f: ", fmeasure)
    prf_file.write(name + "\t" + str(precision) + "\t" + str(recall) + "\t" + str(fmeasure) + "\n")
    prf_file.flush()


In[13]:


indiv = open(base_folder + "LD_indiv_results.tsv",'a')
indiv.write("\tGED\tScore\t\n")
prf_file = open(base_folder + "LD_results.tsv",'a')
prf_file.write("\tPrecision\tRecall\tF-measure\n")
test_folder = base_folder + "LD-test-set/"


In[14]:


evaluate(test_folder, "_answer_prompt.owl")
evaluate(test_folder, "_answer_classes.owl")
evaluate(test_folder, "_answer_examples.owl")
evaluate(test_folder, "_answer_few-shot.owl")

indiv.close()
prf_file.close()


In[15]:


indiv = open(base_folder + "CMOM_indiv_results.tsv",'a')
indiv.write("\tGED\tScore\t\n")
prf_file = open(base_folder + "CMOM_results.tsv",'a')
prf_file.write("\tPrecision\tRecall\tF-measure\n")
test_folder = base_folder + "CMOM-test-set/"


In[16]:


def evaluate_CMOM_classes():
    folder = base_folder + "CMOM-test-set/"
    
    global TP
    TP = 0
    global FP
    FP = 0
    global FN
    FN = 0
    total_score = 0
    test_size = len(os.listdir(folder))
    
    for code in os.listdir(folder):
        CMOMclasses = []
        CMOMclasses_file = open(folder + code + "/" + code + "_CMOMclasses.txt",'r')
        for line in CMOMclasses_file:
            CMOMclasses.append(line.strip())
        LD = parse_answer(test_folder + code + "/" + code + "_trueLD.owl", code) # graph for LD
        graph = create_CMOM_graph(CMOMclasses)
        score = evaluate_CMOM(code, LD, graph)
        total_score = total_score + score
        
    prf("CMOM as just nodes", TP, FP, FN, test_size, total_score)
    
def evaluate_GPT_classes():
    folder = base_folder + "CMOM-test-set/"
    
    global TP
    TP = 0
    global FP
    FP = 0
    global FN
    FN = 0
    total_score = 0
    test_size = len(os.listdir(folder))
    
    for code in os.listdir(folder):
        CMOMclasses = []
        CMOMclasses_file = open(folder + code + "/" + code + "_GPTclasses.txt",'r')
        for line in CMOMclasses_file:
            CMOMclasses.append(line.strip())
        LD = parse_answer(test_folder + code + "/" + code + "_trueLD.owl", code) # graph for LD
        graph = create_CMOM_graph(CMOMclasses)
        score = evaluate_CMOM(code, LD, graph)
        total_score = total_score + score
        
    prf("GPT as just nodes", TP, FP, FN, test_size, total_score)


In[17]:


evaluate_CMOM_classes()
evaluate_GPT_classes()


In[18]:


evaluate(test_folder, "_answer_prompt.owl")
evaluate(test_folder, "_answer_classes.owl")
evaluate(test_folder, "_answer_examples.owl")
evaluate(test_folder, "_answer_few-shot.owl")



indiv.close()
prf_file.close()


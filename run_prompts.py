#!/usr/bin/env python
# coding: utf-8

# In[1]:


#pip install openai


# In[2]:


# prompts
from openai import OpenAI
#system
import os
import sys
# graph
import networkx as nx
# xml
import xml.etree.ElementTree as ET
import itertools


# In[3]:


client = OpenAI(api_key="")


# In[4]:


iri = sys.argv[1]
name = sys.argv[2]
classes = sys.argv[3]
examples = sys.argv[4]
folder = sys.argv[5]
run_zero = sys.argv[6]

code = iri.split("/")[-1]


# In[ ]:


seed = 42


# In[ ]:


# iri = "http://purl.obolibrary.org/obo/HP_0002810"
# name = "dumbbell shaped metaphyses"
# classes = "UBERON_0000178 (blood), CHEBI_41865 (sebacic acid), PATO_0000460 (abnormal), PATO_0000470 (increased amount)"
# examples = ""
# folder = "./LD-test-set/"
# code = iri.split("/")[-1]


# In[ ]:


def save_answer(filename, answer):
    f = open(filename,'w')
    answer = clean_up_answer(answer)
    for line in answer:
        f.write(line)
    f.close()
    
def clean_up_answer(answer):
    answer = answer.replace("```xml\n", "").replace("```", "")
    answer = answer.replace("intersectionOfClass", "intersectionOf")
    answer = answer.replace("IntersectionOf", "intersectionOf")
    answer = answer.replace("Intersection", "intersectionOf")
    answer = answer.replace("intersectionOfOf", "intersectionOf")
    answer = answer.replace("<rdf:List>", "").replace("</rdf:List>", "")
    answer = answer.replace("UnionOf", "unionOf")
    answer = answer.replace("Union", "unionOf")
    answer = answer.replace("EquivalentClass", "equivalentClass")
    answer = answer.replace("< owl", "<owl")
    answer = answer.replace("&owl;","")
    answer = answer.replace("<rdf:Seq>", "").replace("</rdf:Seq>", "")
    answer = answer.replace("<rdf:li>", "").replace("</rdf:li>", "")
    answer = answer.replace("resource","Resource")
    answer = answer.replace("<owl:Thing/>\n", "")
    answer = answer.replace("<owl:Thing rdf:about=", "<rdf:Description rdf:about=")
    answer = answer.replace("<rdf:Resource=", "<rdf:Description rdf:about=")
    answer = answer.replace("<rdf:li rdf:Resource=", "<rdf:Description rdf:about=")
    answer = answer.replace("&obo;", "http://purl.obolibrary.org/obo/").replace("&ro;", "http://purl.obolibrary.org/obo/").replace("&bfo;", "http://purl.obolibrary.org/obo/")
    answer = answer.replace("<rdf:Bag>","").replace("</rdf:Bag>", "")
    answer = answer.replace("<rdf:Alt>", "").replace("</rdf:Alt>", "")
    answer = answer.replace("<owl:members rdf:Resource=", "<rdf:Description rdf:about=")
    answer = answer.replace("<rdf:Description>", "").replace("</rdf:Description>","")
    answer = answer.replace("<?xml version=\"1.0\" ontology=\"http://example.org/ontology\">","<?xml version=\"1.0\"?>").replace("</xml>","")
    answer = answer.replace("<owl:intersectionOf rdf:resource=\"http://www.w3.org/2002/07/owl#Class\"/>", "<owl:intersectionOf rdf:parseType=\"Collection\">")
    answer = answer.replace("<owl:equivalentClass>\n            <owl:Class>", "<owl:equivalentClass>")
    answer = answer.replace("            </owl:Class>\n        </owl:equivalentClass>", "        </owl:equivalentClass>")
    if "xmlns:rdfs" not in answer:
        answer = answer.replace("xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\"", "xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\"\n         xmlns:rdfs=\"http://www.w3.org/2000/01/rdf-schema#\"") 
    return answer

def answer_cannot_be_parsed(filename):
    try:
        tree = ET.parse(filename)
    except:
        return True
    else:
        return False


# In[ ]:


def just_prompt(iri, name, filename):
    completion = client.chat.completions.create(
        model="gpt-4o-mini",
        seed=seed,
        messages=[
            {
                "role": "system",
                "content": "You will receive a request to construct a complex mapping in OWL format. You have to answer with an ontology in OWL format that can be read by rdflib, no explanations. Make sure to use an equivalentClass statement."
            },
            {
                "role": "user",
                "content": "Create a complex mapping in OWL format for the class " + iri + " (" + name + "). You are only allowed to use properties from the Relation Ontology (RO) and the Basic Formal Ontology (BFO)."
            }
        ]
    )
    answer = completion.choices[0].message.content.strip()
    save_answer(filename, answer)


# In[ ]:


def with_classes(iri, name, classes, filename):
    completion = client.chat.completions.create(
        model="gpt-4o-mini",
        seed=seed,
        messages=[
            {
                "role": "system",
                "content": "You will receive a request to construct a complex mapping in OWL format. You have to answer with an ontology in OWL format that can be read by rdflib, no explanations. Make sure to use an equivalentClass statement."
            },
            {
                "role": "user",
                "content": "Create a complex mapping in OWL format for the class " + iri + " (" + name + "). You should use the following classes: " + classes + " and any others you find necessary to match the appropriate pattern in the examples. You are only allowed to use properties from the Relation Ontology (RO) and the Basic Formal Ontology (BFO)."
            }
        ]
    )
    answer = completion.choices[0].message.content.strip()
    save_answer(filename, answer)


# In[ ]:


def with_examples(iri, name, classes, examples, filename):
    completion = client.chat.completions.create(
        model="gpt-4o-mini",
        seed=seed,
        messages=[
            {
                "role": "system",
                "content": "You will receive a request to construct a complex mapping in OWL format. You have to answer with an ontology in OWL format that can be read by rdflib, no explanations. Make sure to use an equivalentClass statement."
            },
            {
                "role": "user",
                "content": "This is a list of possible equivalentClass examples for complex mappings: " + examples + ". Create a complex mapping in OWL format for the class " + iri + " (" + name + ") according to the examples provided. You are only allowed to use properties from the Relation Ontology (RO) and the Basic Formal Ontology (BFO)."
            }
        ]
    )
    answer = completion.choices[0].message.content.strip()
    save_answer(filename, answer)


# In[ ]:


def few_shot(iri, name, classes, examples, filename):
    completion = client.chat.completions.create(
        model="gpt-4o-mini",
        seed=seed,
        messages=[
            {
                "role": "system",
                "content": "You will receive a request to construct a complex mapping in OWL format. You have to answer with an ontology in OWL format that can be read by rdflib, no explanations. Make sure to use an equivalentClass statement."
            },
            {
                "role": "user",
                "content": "This is a list of possible equivalentClass examples for complex mappings: " + examples + ". Create a complex mapping in OWL format for the class " + iri + " (" + name + ") according to the examples provided. You should use the following classes: " + classes + " and any others you find necessary to match the appropriate pattern in the examples. You are only allowed to use properties from the Relation Ontology (RO) and the Basic Formal Ontology (BFO)."
            }
        ]
    )
    answer = completion.choices[0].message.content.strip()
    save_answer(filename, answer)


# In[ ]:


# prompt
if run_zero == "true":
    filename = folder + code + "/" + code + "_answer_prompt.owl"
    just_prompt(iri, name, filename)
    if answer_cannot_be_parsed(filename):
        just_prompt(iri, name, filename)
        if answer_cannot_be_parsed(filename):
            just_prompt(iri, name, filename)
# prompt + classes
filename = folder + code + "/" + code + "_answer_classes.owl"
with_classes(iri, name, classes, filename)
if answer_cannot_be_parsed(filename):
    with_classes(iri, name, classes, filename)
    if answer_cannot_be_parsed(filename):
        with_classes(iri, name, classes, filename)
# prompt + examples
if run_zero == "true":
    filename = folder + code + "/" + code + "_answer_examples.owl"
    with_examples(iri, name, classes, examples, filename)
    if answer_cannot_be_parsed(filename):
        with_examples(iri, name, classes, examples, filename)
        if answer_cannot_be_parsed(filename):
            with_examples(iri, name, classes, examples, filename)
# few-shot
filename = folder + code + "/" + code + "_answer_few-shot.owl"
few_shot(iri, name, classes, examples, filename)
if answer_cannot_be_parsed(filename):
    few_shot(iri, name, classes, examples, filename)
    if answer_cannot_be_parsed(filename):
        few_shot(iri, name, classes, examples, filename)


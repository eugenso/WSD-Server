# WSD-Server
Stand-alone server for Word Sense Disambiguation, using dependency features and a language model (LM)

This project contains the open system that was used for experiments on Word Sense Disambiguation:  <br/>
Panchenko A., Ruppert E., Faralli S., Ponzetto S. P., and Biemann C. (2017): Unsupervised Does Not Mean Uninterpretable: The Case for Word Sense Induction and Disambiguation. In Proceedings of the 15th Conference of the European Chapter of the Association for Computational Linguistics (EACL’2017). Valencia, Spain. Association for Computational Linguistics.

The paper can be accessed here: http://panchenko.me/papers/eacl2017.pdf

It uses a trigram language model and induced semantic models that contain word senses. These datasets can be downloaded from Zenodo: https://zenodo.org/record/485151

## Preparation

- Download the semantic models and the sense inventories
- Add the inventories to a MySQL database
- Load the semantic models into a DCA memory server
- Start the language model
- Adapt the configuration .xml files in resources, so that they point to the MySQL server database

## Running the server

The main class to run the server is tudarmstadt.lt.wsd.server.ApplicationController.java

When the server is running, you can send requests and receive JSON documents:

``curl -i -H "Content-Type: application/json"   -X POST   -d '{"context":"The Java code is not working.", "word":"java", "featureType": "depslm"}' SERVER:PORT/predictWordSense``


[![Build Status](https://travis-ci.org/EHRI/ehri-frontend.svg?branch=master)](https://travis-ci.org/EHRI/frontend)

Front-end for  the [EHRI REST](https://github.com/EHRI/ehri-rest) web service.

This app has a few depependencies in addition to the backend:

 - A MySQL database
 - Solr, running configurated as per the config in [EHRI Search Tools](https://github.com/EHRI/ehri-search-tools)
 - The Java-based command line tool for converting JSON streams between the database and Solr, also
   in the search tools repository

The setup docs to get these dependencies up and running ended up horribly out-of-date, so rather than
actively mislead people they've been temporarily removed pending the completion of some [Docker](http://www.docker.com)
-based dev setup instructions. In the meantime, here's how they'll start:

 - Set up the search engine on port 8983: `sudo docker run -p 127.0.0.1:8983:8983 -it ehri/ehri-search-tools` 
 - Set up the backend web service on port 7474: `sudo docker run -p 0.0.0.0:7474:7474 -it ehri/ehri-rest`
 - [set up MySQL with the right schema]
 - install the `index-data-converter` tool (not sure how to dockerize this.)
 - install Typesafe activator
 - `activator run`
 - go to localhost:9000
 - create an account at http://localhost:9000/login
 - get your new account id (probably `user000001`)
 - run `curl -X POST http://localhost:7474/ehri/group/admin/{userId}` to make dev admin 

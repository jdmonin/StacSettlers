CURRENT_DIR=$(shell pwd)
STAC_SETTLERS_JAR=$(CURRENT_DIR)/target/STACSettlers-1.0-bin.jar

DRL_CONFIG=$(CURRENT_DIR)/config-drl.txt

build:
	JAVA_HOME=`/usr/libexec/java_home -v1.8` mvn install:install-file -Dfile=lib/JavaBayes.jar -DgroupId=local -DartifactId=JavaBayes -Dversion=1.0 -Dpackaging=jar -DgeneratePom=true
	JAVA_HOME=`/usr/libexec/java_home -v1.8` mvn install:install-file -Dfile=lib/mdp-library.jar -DgroupId=local -DartifactId=mdp-library -Dversion=1.0 -Dpackaging=jar -DgeneratePom=true
	JAVA_HOME=`/usr/libexec/java_home -v1.8` mvn install:install-file -Dfile=lib/weka.jar -DgroupId=local -DartifactId=weka -Dversion=1.0 -Dpackaging=jar -DgeneratePom=true
	JAVA_HOME=`/usr/libexec/java_home -v1.8` mvn install:install-file -Dfile=lib/MCTS-1.0.jar -DgroupId=local -DartifactId=MCTS -Dversion=1.0 -Dpackaging=jar -DgeneratePom=true
	JAVA_HOME=`/usr/libexec/java_home -v1.8` mvn install:install-file -Dfile=lib/DeepCatan-0.0.1.jar -DgroupId=local -DartifactId=DeepCatan -Dversion=0.0.1 -Dpackaging=jar -DgeneratePom=true
	JAVA_HOME=`/usr/libexec/java_home -v1.8` mvn package

# Deleting existing stored games
delete_simu:
	$(CURRENT_DIR)/scripts/delete_db_simu.sh
	rm -rf $(CURRENT_DIR)/results/*
	rm -rf $(CURRENT_DIR)/logs_server/*

# Run simulations to gather trajectories
# Need the nodejs server to be on
run_simu:
	JAVA_HOME=`/usr/libexec/java_home -v1.8` java -cp $(STAC_SETTLERS_JAR) soc.robot.stac.simulation.Simulation $(DRL_CONFIG)

# Run the nodejs server
nodejs_server:
	node $(CURRENT_DIR)/web/main/runserver.js

.PHONY: delete_simu run_simu nodejs_server
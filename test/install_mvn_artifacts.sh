mvn install:install-file -Dfile=/home/shen/project/hadoop-2.4.1-src/hadoop-dist/target/hadoop-2.4.1/share/hadoop/hdfs/hadoop-hdfs-2.4.1.jar -DgroupId=org.apache.hadoop -DartifactId=hadoop-hdfs -Dversion=2.4.0 -Dpackaging=jar
mvn install:install-file -Dfile=/home/shen/project/hadoop-2.4.1-src/hadoop-dist/target/hadoop-2.4.1/share/hadoop/common/hadoop-common-2.4.1.jar -DgroupId=org.apache.hadoop -DartifactId=hadoop-common -Dversion=2.4.0 -Dpackaging=jar
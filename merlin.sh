#!/usr/bin/env bash
#
#      Brno University of Technology
#      Faculty of Information Technology
# 
#      BSc Thesis  2006/2007
# 
#      Railway Interlocking Simulator
#
#      Bedrich Hovorka
#
#      ukazka spusteni a prekladu na merlinovi
#-----------------------------------------------------
#
export JAVA_HOME=/usr/local/share/Java2-1.6
export PATH=$JAVA_HOME/bin:$PATH
export CLASSPATH=junit.jar:$CLASSPATH
ant start

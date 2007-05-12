Brno University of Technology
Faculty of Information Technology

BSc Thesis	2006/2007

Railway Interlocking Simulator

Bedrich Hovorka

soubor README
#################################################################

DVD obsahuje:

build.xml  - ant buildfile pro kompilaci programu
junit.jar  - knihovna junit
merlin.sh  - ukazka jak spustit projekt na merlinovi
README.txt - tento soubor
src - zdrojove soubory programu a knihovny jDisco (*.java, *.xml, *.xsd)
text - zdrojove soubory a obrazky textu 
doc - vygenerovana programova dokumentace (ant doc)

#################################################################

KOMPILACE:

text:
   jsou potreba : make, gnuplot, latex, wmf2eps, sed, ...

   > cd text
   > make

program:
   jsou potreba : ant, javac (1.6), junit nastaveny v CLASSPATH 

   > ant build

################################################################

SPUSTENI PROGRAMU:

vyhybna:
   > ant start

editor:
   > ant run

SYNOPSIS:
   > cd build
   > java -ea cz.vutbr.fit.interlockSim.Main (sim|edit) [file]

   nebo
  
   > java -ea cz.vutbr.fit.interlockSim.Main example name [endTime]

na merlinovi bude asi treba jeste pridat: -Xmx300
   
   simulace vyhybny - 5 minut modeloveho casu:
   > java -ea -Xmx300 cz.vutbr.fit.interlockSim.Main example shuntingLoop 300
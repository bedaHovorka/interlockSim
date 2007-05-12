#
#      Brno University of Technology
#      Faculty of Information Technology
# 
#      BSc Thesis	2006/2007
# 
#      Railway Interlocking Simulator
#
#      Bedrich Hovorka
#
#      GNUplot demonstrating numerical method
#
#-------------------------------------------------
set terminal epslatex color
set output "shunting.eps"
plot 'shunting.dat' notitle smooth csplines 
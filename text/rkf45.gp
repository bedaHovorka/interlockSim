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
set output "rkf45.eps"

x_n = 0.0
y_n = 0.0
h = 5.0

f(x,y) = -x #druhy arg je ted asi nanic...
F(x) = -x**2/2.0

k_1 = f(x_n, y_n)
x_2 = x_n + 0.25*h
k_2 = f(x_2, y_n + 0.25*h*k_1)
k_3 = f(x_n + 3/8.0*h, y_n + 1/32*h*(3*k_1 + 9*k_2))
k_4 = f(x_n + 12.0/13*h, y_n + 1/2197*h*(1932*k_1 - 7200*k_2 + 7296*k_3))
k_5 = f(x_n + h, y_n + h*(439/216*k_1 - 8*k_2 + 3680/513*k_3 - 845/4104*k_4))
k_6 = f(x_n + 0.5*h, y_n + h*(-8/27*k_1 + 2*k_2 - 3544/2565*k_3 + 1859/4104*k_4 - 11/40*k_5))
y_n1 = y_n + h*(16.0/135*k_1 + 6656.0/12852*k_3 + 28561.0/56430*k_4 - 9.0/50*k_5 + 2.0/55*k_6)

line(x1, y1, x2, y2, x) = (y2-y1)/(x2-x1) * x + y1

plot [x_n-1 : x_n+h+1] F(x) title "analytic", \
	line(x_n, y_n, x_n + h, y_n1, x) title "result aprox in $f(0)$", \
	line(x_n, y_n, x_2, k_2, x) title "substep $k_2$"
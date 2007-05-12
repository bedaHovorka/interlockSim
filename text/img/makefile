#
#      Brno University of Technology
#      Faculty of Information Technology
# 
#      BSc Thesis	2006/2007
# 
#      Railway Interlocking Simulator
#
#      Bedrich Hovorka
#-----------------------------------------------------
#
LANG         = cs_CZ.iso-8859-2
LOCALE       = cs_CZ
WMFEPSS	     = $(patsubst %.wmf,%.eps, $(wildcard *.wmf))
BMPEPSS	     = $(patsubst %.bmp,%.eps, $(wildcard *.bmp))

###########################################

all:         $(WMFEPSS) $(BMPEPSS) $(patsubst %.eps,%.pdf,$(wildcard *.eps) $(WMFEPSS) $(BMPEPSS))

###########################################

.SUFFIXES: .bmp .eps .pdf .wmf

.eps.pdf:
	epstopdf $<

.wmf.eps:
	wmf2eps --wmf-xtra-fonts $< > $(patsubst %.wmf,%.eps,$<)

.bmp.eps:
	autotrace -dpi 1200 -filter-iterations 64 $< -output-format eps > $@
###########################################

clean:
	rm -f *.pdf $(WMFEPSS) $(BMPEPSS) *~
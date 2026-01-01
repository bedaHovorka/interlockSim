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
DOC          = bakalarka
PROGS        = 			#TODO
PLOTS        = $(wildcard *.gp)
PLOTS_TEX  = $(PLOTS:.gp=.tex)
PLOTS_EPS  = $(PLOTS:.gp=.eps)
PLOTS_PDF  = $(PLOTS:.gp=.pdf)

###########################################

all:		$(PROGS) $(DOC).ps

rebuild:	clean all

$(PROGS):
		ant build # TODO SET ENVIRONMENT??

test:
		ant start

###########################################

.SUFFIXES: .eps .pdf .ps .dvi

.eps.pdf:
	epstopdf $<

.dvi.ps:
	dvips $<

###########################################

pdf: $(DOC).pdf

$(PLOTS_TEX) $(PLOTS_EPS) : $(PLOTS)
	gnuplot $(PLOTS)

$(DOC).tex: $(DOC).utf8.tex
	iconv -futf-8 -tiso8859-2 $(DOC).utf8.tex > $(DOC).tex
	vlna -l -r $(DOC).tex

zkratky.tex: zkratky.dat
	sort zkratky.dat | sed -e 's/^\(.*\) \"\(.*\)\"\(.*\)/\\paragraph{\1} \2 -- \3/g' > zkratky.tex

images: $(PLOTS_PDF)
	make -C img all

$(DOC).pdf: clean $(DOC).tex zkratky.tex $(DOC).bib images
	pdflatex $(DOC).tex
	bibtex $(DOC)
	pdflatex $(DOC).tex
	pdflatex $(DOC).tex

$(DOC).dvi: $(DOC).tex zkratky.tex $(DOC).bib images
	latex $(DOC)
	bibtex $(DOC)
	latex $(DOC)
	latex $(DOC)

clean:
	rm -f *.dvi *.log $(DOC).blg $(DOC).bbl $(DOC).toc *.aux $(DOC).out $(DOC).tex
	rm -f $(DOC).lof $(PLOTS_TEX) $(PLOTS_EPS) $(PLOTS_PDF) zkratky.tex *~
	make -C img clean

spell:
	export LANG=cs_CZ.UTF-8; aspell --lang=cs --encoding=utf-8 --mode=tex check $(DOC).utf8.tex
	export LANG=cs_CZ.UTF-8; aspell --lang=cs --encoding=iso-8859-2 --mode=tex check zkratky.dat


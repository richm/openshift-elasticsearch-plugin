VERSION=2.3.5.1
all: maven-build

respin: clean all

maven-build: chain/chain.ini
	git add chain/chain.ini
	git commit -s -m "Update chain.ini hashes" && git push origin $(VERSION)-build:$(VERSION)-build || :
	brew maven-chain lpc-rhel-7-maven-candidate chain/chain.ini

chain/chain.ini: make-vars
	eval $$(./make-vars) && sed -e s/@VERSION@/$$VERSION/ -e s/@CODE_HASH@/$$CODE_HASH/ -e s/@MEAD_HASH@/$$MEAD_HASH/ chain/chain.ini.in > chain/chain.ini

clean:
	rm -f chain/chain.ini

rm -rf target/release
mkdir target/release
cd target/release
git clone git@github.com:grails-plugins/grails-standalone.git
cd grails-standalone
grails clean
grails compile
#grails publish-plugin --snapshot --stacktrace --allow-overwrite
grails publish-plugin --stacktrace

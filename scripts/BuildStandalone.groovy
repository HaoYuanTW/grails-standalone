/* Copyright 2011-2016 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import grails.util.BuildSettings
import grails.util.GrailsUtil
import grails.util.PluginBuildSettings

import org.apache.ivy.core.report.ResolveReport
import org.codehaus.groovy.grails.resolve.IvyDependencyManager
import org.springframework.util.FileCopyUtils

/**
 * @author <a href='mailto:burt@burtbeckwith.com'>Burt Beckwith</a>
 */

includeTargets << grailsScript('_GrailsWar')

target(buildStandalone: 'Build a standalone app with embedded server') {
	depends configureProxy, compile, loadPlugins

	try {
		if (grailsEnv == 'development' && !argsMap.warfile) {
			event 'StatusUpdate', ["You're running in the development environment but haven't specified " +
			                       "a war file, so one will be built with development settings."]
		}

		File workDir = new File(grailsSettings.projectTargetDir, 'standalone-temp-' + System.currentTimeMillis()).absoluteFile
		if (!workDir.deleteDir()) {
			event 'StatusError', ["Unable to delete $workDir"]
			return
		}
		if (!workDir.mkdirs()) {
			event 'StatusError', ["Unable to create $workDir"]
			return
		}

		String jarname = argsMap.params[0] ?: buildSettings.config.grails.plugin.standalone.jarname ?: ''
		File jar = jarname ? new File(jarname).absoluteFile : new File(workDir.parentFile, 'standalone-' + grailsAppVersion + '.jar').absoluteFile

		boolean jetty = (argsMap.jetty || buildSettings.config.grails.plugin.standalone.useJetty) && !argsMap.tomcat

		event 'StatusUpdate', ["Building standalone jar $jar.path for ${jetty ? 'Jetty' : 'Tomcat'}"]

		File warfile
		if (argsMap.warfile) {
			warfile = new File(argsMap.warfile).absoluteFile
			if (warfile.exists()) {
				println "Using war file $argsMap.warfile"
				if (!buildJar(workDir, jar, jetty, warfile)) {
					return
				}
			}
			else {
				errorAndDie "War file $argsMap.warfile not found"
			}
		}
		else {
			warfile = buildWar(workDir)
			if (!buildJar(workDir, jar, jetty)) {
				return
			}
		}

		if (!workDir.deleteDir()) {
			event 'StatusError', ["Unable to delete $workDir"]
		}

		event 'StatusUpdate', ["Built $jar.path"]
	}
	catch (e) {
		throw GrailsUtil.deepSanitize(e)
	}
}

buildWar = { File workDir ->
	File warfile = new File(workDir, 'embedded.war').absoluteFile
	warfile.deleteOnExit()

	argsMap.params.clear()
	argsMap.params << warfile.path
	war()
	removeTomcatJarsFromWar workDir, warfile

	warfile
}

buildJar = { File workDir, File jar, boolean jetty, File warfile = null ->

	List<String> dependencyJars = resolveJars(jetty, buildSettings.config.grails.plugin.standalone)

	ant.path(id: 'standalone.cp') { dependencyJars.each { pathelement(path: it) } }

	// compile the launcher so it's directly in the JAR

	// Ant 1.8 (used in Grails versions before 2.5.1) isn't compatible with Java8, so set the compiler based on the version of Java
	ant.antversion(property: 'antversion')
	BigDecimal antVersion = new BigDecimal(ant.antProject.properties.antversion[0..2])
	BigDecimal javaVersion = new BigDecimal(System.getProperty('java.version')[0..2])
	if (antVersion.compareTo(new BigDecimal('1.9')) == -1) {
		 javaVersion = javaVersion.min(new BigDecimal('1.7'))
	}

	ant.javac(destdir: workDir, compiler: 'javac' + javaVersion, source: '1.5', target: '1.5',
	          debug: true, listfiles: true, classpathref: 'standalone.cp', includeAntRuntime: false) {
		src(path: new File(standalonePluginDir, 'src/java').path)
		src(path: new File(standalonePluginDir, 'src/runtime').path)
		include(name: 'grails/plugin/standalone/AbstractLauncher.java')
		if (jetty) {
			include(name: 'grails/plugin/standalone/JettyLauncher.java')
		}
		else {
			include(name: 'grails/plugin/standalone/Launcher.java')
		}
	}

	for (jarPath in dependencyJars) {
		ant.unjar src: jarPath, dest: workDir
	}

	if (jetty) {
		// Jetty requires a 'defaults descriptor' on the filesystem
		File webDefaults = new File(workDir, 'webdefault.xml')
		File pluginDir = new PluginBuildSettings(buildSettings).getPluginDirForName('standalone').file
		FileCopyUtils.copy new File(pluginDir, 'grails-app/conf/webdefault.xml'), webDefaults
	}

	jar.canonicalFile.parentFile.mkdirs()
	ant.jar(destfile: jar) {
		fileset dir: workDir
		if (warfile) {
			zipfileset file: warfile, fullpath: 'embedded.war'
		}
		manifest {
			attribute name: 'Main-Class', value: resolveMainClass(jetty)
		}
	}

	true
}

removeTomcatJarsFromWar = { File workDir, File warfile ->
	def expandedDir = new File(workDir, 'expanded').absoluteFile
	ant.unzip src: warfile, dest: expandedDir
	for (file in new File(expandedDir, 'WEB-INF/lib').listFiles()) {
		if (file.name.startsWith('tomcat-') && !['embed-logging', 'jdbc', 'pool'].any { file.name.contains it }) {
			assert file.delete()
		}
	}
	assert warfile.delete()
	ant.zip basedir: expandedDir, destfile: warfile
	assert expandedDir.deleteDir()
}

resolveJars = { boolean jetty, standaloneConfig ->

	def deps = [standaloneConfig.ecjDependency ?: 'org.eclipse.jdt.core.compiler:ecj:4.5.1']

	if (jetty) {
		deps.addAll calculateJettyDependencies(standaloneConfig)
	}
	else {
		deps.addAll calculateTomcatDependencies(standaloneConfig)
	}

	if (standaloneConfig.extraDependencies instanceof Collection) {
		deps.addAll standaloneConfig.extraDependencies
	}

	def manager = new IvyDependencyManager('standalone', '0.1', new BuildSettings())
	manager.parseDependencies {
		log standaloneConfig.ivyLogLevel ?: 'warn'
		repositories {
			mavenLocal()
			mavenCentral()
		}
		dependencies {
			compile(*deps) {
				transitive = false
			}
		}
	}

	ResolveReport report = manager.resolveDependencies()
	if (report.hasError()) {
		// TODO
		return null
	}

	def paths = []
	for (File file in report.allArtifactsReports.localFile) {
		if (file) paths << file.path
	}

	paths
}

String resolveMainClass(boolean jetty) {
	buildSettings.config.grails.plugin.standalone.mainClass ?: 'grails.plugin.standalone.' + (jetty ? 'JettyLauncher' : 'Launcher')
}

calculateJettyDependencies = { standaloneConfig ->
	String servletVersion = buildSettings.servletVersion
	String servletApiDep = standaloneConfig.jettyServletApiDependency ?:
		'javax.servlet:' + (servletVersion.startsWith('3') ? 'javax.servlet-api:3.1.0' : 'servlet-api:' + servletVersion)
	String jettyVersion = standaloneConfig.jettyVersion ?: '7.6.0.v20120127'
	['org.eclipse.jetty.aggregate:jetty-all:' + jettyVersion, servletApiDep]
}

calculateTomcatDependencies = { standaloneConfig ->

	String tomcatVersion = standaloneConfig.tomcatVersion ?: '9.0.0.M4'

	def deps = []

	def defaultTomcatDeps = [
		'tomcat-annotations-api', 'tomcat-api', 'tomcat-catalina-ant', 'tomcat-catalina',
		'tomcat-coyote', 'tomcat-juli', 'tomcat-servlet-api', 'tomcat-util']
	for (name in (standaloneConfig.tomcatDependencies ?: defaultTomcatDeps)) {
		deps << 'org.apache.tomcat:' + name + ':' + tomcatVersion
	}

	def defaultTomcatEmbedDeps = [
		'tomcat-embed-core', 'tomcat-embed-el', 'tomcat-embed-jasper', 'tomcat-embed-logging-juli',
		'tomcat-embed-logging-log4j', 'tomcat-embed-websocket']
	for (name in (standaloneConfig.tomcatEmbedDependencies ?: defaultTomcatEmbedDeps)) {
		deps << 'org.apache.tomcat.embed:' + name + ':' + tomcatVersion
	}

	deps
}

setDefaultTarget buildStandalone

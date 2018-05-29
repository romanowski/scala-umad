#!/usr/bin/python

import optparse
import os
import re
import subprocess
import sys
import tempfile

from glob import glob

parser = optparse.OptionParser()
parser.add_option('-s', '--scala', dest='scala', help='Path to the Scala build dir')
parser.add_option('-b', '--baseline-scala', dest='baseline', default="", help='Path to the baseline Scala build dir')
parser.add_option('-d', '--debugPort', dest='debugPort', help='Port on which remote debugger can be attached')
parser.add_option('-c', '--corpus', dest='corpus', default="scala-library", help='Project to compile')
parser.add_option('-o', '--overrides', dest='overrides', default="", help='Config overrides (key=values paris separated by ;')


(options, args) = parser.parse_args()

def findFiles(path, regex):
	rx = re.compile(regex)
	files = []
	for path, dnames, fnames in os.walk(path):
	    files.extend([os.path.join(path, file) for file in fnames if rx.search(file)])
	return files

corpus = os.path.join("corpus", options.corpus)

sources = findFiles(os.path.join(corpus, "src"), r'\.(scala$|java$)')

jars = findFiles(os.path.join(corpus, "lib"), r'\.jar')

scalaJars = findFiles(os.path.join(options.scala, "lib"), r'\.jar')

scalacOptions = ["-encoding", "UTF-8", "-target:jvm-1.8", "-feature", "-unchecked",
				 "-Xlog-reflective-calls", "-Xlint", "-opt:l:none", "-J-XX:MaxInlineSize=0"]

debugOptions = []
if options.debugPort:
    debugOptions =  ["-J-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=5005".format(options.debugPort)]

classpathSeparator = ";" if os.name == 'nt' else ":"

subprocess.call(["mvn", "package"], cwd="umad")

outputBase = tempfile.mkdtemp()
scalaOutput = os.path.join(outputBase, "scala")
baselineOutput = os.path.join(outputBase, "baseline")

os.mkdir(scalaOutput)
os.mkdir(baselineOutput)

def call_compiler(scalaLocation, output, additionalOptions=[]):
    agentJar = os.path.join(".", "umad", "target", "umad-1.0-SNAPSHOT.jar")
    configOverrides = map(lambda v: "-J-D"+v, options.overrides.split(';') + additionalOptions)
    subprocess.call([
						os.path.join(scalaLocation, "bin", "scalac"),
						"-cp", classpathSeparator.join(scalaJars + jars),
						"-d", output,
						"-J-javaagent:" + agentJar,
						 "-toolcp", agentJar
                     ] +
                    configOverrides +
					scalacOptions +
					sources +
					debugOptions +
					args)

call_compiler(options.scala, scalaOutput)

print "Compilation done."

if options.baseline != "":
	print "Compiling baseline... "
	call_compiler(options.baseline, baselineOutput, ["umad.monitor.enabled=false"])

	result = subprocess.call([
						"diff", "-r",
						scalaOutput,
						baselineOutput,
					])

	if result != 0:
		print "[ERROR] Produced binary differs!"
		sys.exit(result)
	else:
		print "[SUCCESS] No differences reported!"
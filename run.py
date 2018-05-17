#!/usr/bin/python

import optparse
import os
import re
import subprocess
import sys

from glob import glob

parser = optparse.OptionParser()
parser.add_option('-s', '--scala', dest='scala', help='Path to the Scala build dir')
parser.add_option('-d', '--debugPort', dest='debugPort', help='Port on which remote debugger can be attached')
parser.add_option('-c', '--corpus', dest='corpus', default="scala-library", help='Project to compile')


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

umadJar = os.path.join(".", "umad", "target", "umad-1.0-SNAPSHOT.jar")

scalacOptions = ["-encoding", "UTF-8", "-target:jvm-1.8", "-feature", "-unchecked",
				 "-Xlog-reflective-calls", "-Xlint", "-opt:l:none", "-J-XX:MaxInlineSize=0"]

debugOptions = []
if options.debugPort:
    debugOptions =  ["-J-agentlib:jdwp=transport=dt_socket,server=n,address=localhost:{},suspend=y".format(options.debugPort)]

classpathSeparator = ";" if os.name == 'nt' else ":"

subprocess.call(["mvn", "package"], cwd="umad")

subprocess.call([
	os.path.join(options.scala, "bin", "scalac"),
	"-J-javaagent:" + umadJar,
	"-toolcp", umadJar,
	"-cp", classpathSeparator.join(scalaJars + jars)] +
	scalacOptions +
	sources +
	debugOptions +
	args)
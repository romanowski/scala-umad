#!/usr/bin/python

import optparse
import os
import re
import subprocess
import sys

from glob import glob

parser = optparse.OptionParser()
parser.add_option('-s', '--scala', dest='scala', help='Path to the Scala build dir')

(options, args) = parser.parse_args()

def findFiles(path, regex):
	rx = re.compile(regex)
	files = []
	for path, dnames, fnames in os.walk(path):
	    files.extend([os.path.join(path, file) for file in fnames if rx.search(file)])
	return files

akkaActor = os.path.join("corpus", "akka", "akka-actor")

akkaActorSources = findFiles(os.path.join(akkaActor, "src"), r'\.(scala$|java$)')

akkaActorJars = findFiles(os.path.join(akkaActor, "lib"), r'\.jar')

scalaJars = findFiles(os.path.join(options.scala, "lib"), r'\.jar')

umadJar = os.path.join(".", "umad", "target", "umad-1.0-SNAPSHOT.jar")

scalacOptions = ["-encoding", "UTF-8", "-target:jvm-1.8", "-feature", "-unchecked", "-Xlog-reflective-calls", "-Xlint"]

classpathSeparator = ";" if os.name == 'nt' else ":"

subprocess.call(["mvn", "package"], cwd="umad")

subprocess.call([
	os.path.join(options.scala, "bin", "scalac"),
	"-J-javaagent:" + umadJar,
	"-toolcp", umadJar,
	"-cp", classpathSeparator.join(scalaJars + akkaActorJars)] +
	scalacOptions +
	akkaActorSources)
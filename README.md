# scala-umad

Tool for quick and simple testing changes for parallelizing Scala.

Setup:

```
cd <WORKSPACE>
git clone --recurse-submodules https://github.com/pkukielka/scala-umad.git
git clone -b pkukielka/2.13.x-parser-baseline https://github.com/rorygraves/scalac_perf.git

cd <WORKSPACE>/scalac_perf
sbt dist/mkPack

cd <WORKSPACE>/scala-umad
./run.py -s <WORKSPACE>/scalac_perf/build/pack/ 
```
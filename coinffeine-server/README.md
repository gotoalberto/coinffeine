Coinffeine Server Module
========================

How to create a standalone JAR:

    $ sbt server/assembly

Then you can find the JAR at
`target/scala-2.10/coinffeine-server-standalone.jar` and use it as:

    java -jar <jar path> [-p port]

How to create a DEB package:

 * Make sure you have installed `dpkg` and `fakeroot`.
 * Use the following command

    $ sbt server/debian:packageBin

And get a DEB named `coinffeine-server-<version>.deb` at `coinffeine-server/target`.

Enjoy!


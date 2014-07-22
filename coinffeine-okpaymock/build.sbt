name := "coinffeine-okpaymock"

libraryDependencies ++= Dependencies.akka ++ Seq(
  Dependencies.akkaRemote,
  Dependencies.bitcoinj,
  Dependencies.h2 % "test",
  Dependencies.jaxws,
  Dependencies.jodaConvert,
  Dependencies.scalaz
)

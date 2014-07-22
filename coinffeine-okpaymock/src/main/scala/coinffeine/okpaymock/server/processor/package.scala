package coinffeine.okpaymock.server

import scalaz.Validation

package object processor {

  type FaultOr[T] = Validation[OkPayFault, T]
}

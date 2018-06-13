package hmda.validation.rules.lar.validity

import hmda.model.filing.lar.LoanApplicationRegister
import hmda.validation.dsl.ValidationResult
import hmda.validation.rules.EditCheck
import hmda.validation.dsl.PredicateCommon._
import hmda.validation.dsl.PredicateSyntax._

object V613_1 extends EditCheck[LoanApplicationRegister] {

  val preapprovalValues = List(1, 2)

  override def name: String = "V613-1"

  override def parent: String = "V613"

  override def apply(lar: LoanApplicationRegister): ValidationResult = {
    lar.action.preapproval.code is containedIn(preapprovalValues)
  }
}

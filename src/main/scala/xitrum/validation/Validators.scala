package xitrum.validation

import scala.xml.{Attribute, Elem, Null, Text}
import xitrum.Action

class Validators(var validators: List[Validator]) {
  def ::(validator: Validator): Validators = {
    validators = validator :: validators
    this
  }

  /**
   * {<input type="text" name="username" /> :: Required :: MaxLength(10)}
   *
   * This method changes "name" attribute of the element to inject serialized
   * validators, while validators may add new or change other attributes. For
   * example, Required adds class="required".
   */
  def ::(elem: Elem)(implicit action: Action): Elem = {
    val paramName       = (elem \ "@name").text
    val secureParamName = ValidatorInjector.injectToParamName(paramName, validators:_*)

    val elem2 = validators.foldLeft(elem) { (acc, validator) =>
      validator.render(action, acc, paramName, secureParamName)
    }
    elem2 % Attribute(None, "name", Text(secureParamName), Null)
  }
}

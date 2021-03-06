/*
 * Copyright 2015 Branko Juric, Brady Wood
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package gwen.eval.support

import scala.sys.process.stringSeqToProcess
import scala.sys.process.stringToProcess
import gwen.Predefs.RegexContext
import gwen.dsl.{FlatTable, Passed, Step}
import gwen.eval.{EnvContext, EvalEngine}
import gwen.errors._
import gwen.Settings
import gwen.Predefs.Kestrel

import scala.io.Source
import scala.util.Try

/** Provides the common default steps that all engines can support. */
trait DefaultEngineSupport[T <: EnvContext] extends EvalEngine[T] {

   /**
    * Defines the default priority steps supported by all engines. For example, a step that calls another step needs
    * to execute with priority to ensure that there is no match conflict between the two (which can occur if the
     * step being called by a step is a StepDef or another step that matches the entire calling step).
    *
    * @param step the step to evaluate
    * @param env the environment context
    */
  override def evaluatePriority(step: Step, env: T): Option[Step] = Option {

    step.expression match {

      case r"""(.+?)$doStep for each data record""" => doEvaluate(step, env) { _ =>
        val dataTable = env.featureScope.getObject("table") match {
          case Some(table: FlatTable) => table
          case Some(other) => dataTableError(s"Cannot use for each on object of type: ${other.getClass.getName}")
          case _ => dataTableError("Calling step has no data table")
        }
        val records = () => {
          dataTable.records.indices.map(idx => dataTable.recordScope(idx))
        }
        foreach(records, "record", step, doStep, env)
      }

      case r"""(.+?)$doStep for each (.+?)$entry in (.+?)$source delimited by "(.+?)"$$$delimiter""" => doEvaluate(step, env) { _ =>
        val sourceValue = env.getBoundReferenceValue(source)
        val values = () => {
          sourceValue.split(delimiter).toSeq
        }
        foreach(values, entry, step, doStep, env)
      }

      case r"""(.+?)$doStep if (.+?)$$$condition""" => doEvaluate(step, env) { _ =>
        val javascript = env.activeScope.get(s"$condition/javascript")
        env.evaluate(evaluateStep(Step(step.pos, step.keyword, doStep), env)) {
          if (env.evaluateJSPredicate(env.interpolate(javascript)(env.getBoundReferenceValue))) {
            logger.info(s"Processing conditional step ($condition = true): ${step.keyword} $doStep")
            evaluateStep(Step(step.pos, step.keyword, doStep), env)
          } else {
            logger.info(s"Skipping conditional step ($condition = false): ${step.keyword} $doStep")
            Step(step, Passed(0), Nil)
          }
        }
      }

      case _ => null
    }
  }
  
  /**
    * Defines the default steps supported by all engines.
    *
    * @param step the step to evaluate
    * @param env the environment context
    * @throws gwen.errors.UndefinedStepException if the given step is undefined
    *         or unsupported
    */
  override def evaluate(step: Step, env: T): Unit = {

    step.expression match {

      case r"""my (.+?)$name (?:property|setting) (?:is|will be) "(.*?)"$$$value""" => step.orDocString(value) tap { value =>
        Settings.add(name, value, overrideIfExists = true)
      }
        
      case r"""(.+?)$attribute (?:is|will be) "(.*?)"$$$value""" => step.orDocString(value) tap { value =>
        env.featureScope.set(attribute, value)
      }

      case r"""I wait ([0-9]+?)$duration second(?:s?)""" =>
        env.perform {
          Thread.sleep(duration.toLong * 1000)
        }
      
      case r"""I execute system process "(.+?)"$$$systemproc""" => step.orDocString(systemproc) tap { systemproc =>
        env.perform {
          systemproc.! match {
            case 0 =>
            case _ => systemProcessError(s"The call to system process '$systemproc' has failed.")
          }
        }
      }

      case r"""I execute a unix system process "(.+?)"$$$systemproc""" => step.orDocString(systemproc) tap { systemproc =>
        env.perform {
          Seq("/bin/sh", "-c", systemproc).! match {
            case 0 =>
            case _ => systemProcessError(s"The call to system process '$systemproc' has failed.")
          }
        }
      }

      case r"""I execute javascript "(.+?)$javascript"""" => step.orDocString(javascript) tap { javascript =>
        env.evaluateJS(javascript)
      }


      case r"""I capture (.+?)$attribute by javascript "(.+?)"$$$expression""" => step.orDocString(expression) tap { expression =>
        val value = Option(env.evaluateJS(env.formatJSReturn(env.interpolate(expression)(env.getBoundReferenceValue)))).map(_.toString).orNull
        env.featureScope.set(attribute, value tap { content =>
          env.addAttachment(attribute, "txt", content)
        })
      }

      case r"""I capture the (text|node|nodeset)$targetType in (.+?)$source by xpath "(.+?)"$expression as (.+?)$$$name""" =>
        val src = env.getBoundReferenceValue(source)
        val result = env.evaluateXPath(expression, src, env.XMLNodeType.withName(targetType)) tap { content =>
          env.addAttachment(name, "txt", content)
        }
        env.featureScope.set(name, result)

      case r"""I capture the text in (.+?)$source by regex "(.+?)"$expression as (.+?)$$$name""" =>
        val src = env.getBoundReferenceValue(source)
        val result = env.extractByRegex(expression, src) tap { content =>
          env.addAttachment(name, "txt", content)
        }
        env.featureScope.set(name, result)

      case r"""I capture the content in (.+?)$source by json path "(.+?)"$expression as (.+?)$$$name""" =>
        val src = env.getBoundReferenceValue(source)
        val result = env.evaluateJsonPath(expression, src) tap { content =>
          env.addAttachment(name, "txt", content)
        }
        env.featureScope.set(name, result)

      case r"""I capture (.+?)$source as (.+?)$$$attribute""" =>
        val value = env.getBoundReferenceValue(source)
        env.featureScope.set(attribute, value tap { content =>
          env.addAttachment(attribute, "txt", content)
        })

      case r"""I capture (.+?)$$$attribute""" =>
        val value = env.getBoundReferenceValue(attribute)
        env.featureScope.set(attribute, value tap { content =>
          env.addAttachment(attribute, "txt", content)
        })

      case r"""I base64 decode (.+?)$attribute as (.+?)$$$name""" =>
        val source = env.getBoundReferenceValue(attribute)
        val result = env.decodeBase64(source) tap { content =>
          env.addAttachment(name, "txt", content)
        }
        env.featureScope.set(name, result)

      case r"""I base64 decode (.+?)$attribute""" =>
        val source = env.getBoundReferenceValue(attribute)
        val result = env.decodeBase64(source) tap { content =>
          env.addAttachment(attribute, "txt", content)
        }
        env.featureScope.set(attribute, result)

      case r"""(.+?)$attribute (?:is|will be) defined by (javascript|system process|property|setting|file)$attrType "(.+?)"$$$expression""" => step.orDocString(expression) tap { expression =>
        attrType match {
          case "javascript" => env.activeScope.set(s"$attribute/javascript", expression)
          case "system process" => env.activeScope.set(s"$attribute/sysproc", expression)
          case "file" => env.activeScope.set(s"$attribute/file", expression)
          case _ => env.featureScope.set(attribute, Settings.get(expression))
        }
      }

      case r"""(.+?)$attribute (?:is|will be) defined by the (text|node|nodeset)$targetType in (.+?)$source by xpath "(.+?)"$$$expression""" => step.orDocString(expression) tap { expression =>
        env.activeScope.set(s"$attribute/xpath/source", source)
        env.activeScope.set(s"$attribute/xpath/targetType", targetType)
        env.activeScope.set(s"$attribute/xpath/expression", expression)
      }

      case r"""(.+?)$attribute (?:is|will be) defined in (.+?)$source by regex "(.+?)"$$$expression""" => step.orDocString(expression) tap { expression =>
        env.activeScope.set(s"$attribute/regex/source", source)
        env.activeScope.set(s"$attribute/regex/expression", expression)
      }

      case r"""(.+?)$attribute (?:is|will be) defined in (.+?)$source by json path "(.+?)"$$$expression""" => step.orDocString(expression) tap { expression =>
        env.activeScope.set(s"$attribute/json path/source", source)
        env.activeScope.set(s"$attribute/json path/expression", expression)
      }

      case r"""(.+?)$attribute (?:is|will be) defined by sql "(.+?)"$selectStmt in the (.+?)$dbName database""" =>
        Settings.get(s"gwen.db.${dbName}.driver")
        Settings.get(s"gwen.db.${dbName}.url")
        env.activeScope.set(s"$attribute/sql/selectStmt", selectStmt)
        env.activeScope.set(s"$attribute/sql/dbName", dbName)

      case r"""(.+?)$attribute (?:is|will be) defined in the (.+?)$dbName database by sql "(.+?)"$$$selectStmt""" => step.orDocString(selectStmt) tap { selectStmt =>
        Settings.get(s"gwen.db.${dbName}.driver")
        Settings.get(s"gwen.db.${dbName}.url")
        env.activeScope.set(s"$attribute/sql/selectStmt", selectStmt)
        env.activeScope.set(s"$attribute/sql/dbName", dbName)
      }

      case r"""I update the (.+?)$dbName database by sql "(.+?)"$$$updateStmt""" => step.orDocString(updateStmt) tap { updateStmt =>
        Settings.get(s"gwen.db.${dbName}.driver")
        Settings.get(s"gwen.db.${dbName}.url")
        val rowsAffected = env.executeSQLUpdate(updateStmt, dbName)
        env.activeScope.set(s"$dbName rows affected", rowsAffected.toString)
      }

      case r"""(.+?)$source at (json path|xpath)$matcher "(.+?)"$path should( not)?$negation (be|contain|start with|end with|match regex)$operator "(.*?)"$$$expression""" => step.orDocString(expression) tap { expression =>
        val src = env.activeScope.get(source)
        env.perform {
          val actual = matcher match {
            case "json path" => env.evaluateJsonPath(path, src)
            case "xpath" => env.evaluateXPath(path, src, env.XMLNodeType.text)
          }
          val negate = Option(negation).isDefined
          val result = env.compare(expression, actual, operator, negate)
          assert(result, s"Expected $source at $matcher '$path' to ${if(negate) "not " else ""}$operator '$expression' but got '$actual'")
        }
      }

      case r"""(.+?)$attribute should( not)?$negation (be|contain|start with|end with|match regex|match xpath|match json path)$operator "(.*?)"$$$expression""" => step.orDocString(expression) tap { expression =>
        val actualValue = env.getBoundReferenceValue(attribute)
        env.perform {
          val negate = Option(negation).isDefined
          val result = env.compare(expression, actualValue, operator, negate)
          assert(result, s"Expected $attribute to ${if(negate) "not " else ""}$operator '$expression' but got '$actualValue'")
        }
      }

      case r"""(.+?)$attribute should be absent""" =>
        env.perform {
          assert(Try(env.getBoundReferenceValue(attribute)).isFailure, s"Expected $attribute to be absent")
        }
      
      case _ => undefinedStepError(step)
      
    }
  }
  
}
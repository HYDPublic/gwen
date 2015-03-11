/*
 * Copyright 2014-2015 Branko Juric, Brady Wood
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

package gwen.eval

import java.io.File

import com.typesafe.scalalogging.slf4j.LazyLogging

import gwen.Predefs.Exceptions
import gwen.Predefs.FileIO
import gwen.Predefs.Kestrel
import gwen.dsl.Failed
import gwen.dsl.Scenario
import gwen.dsl.Step
import play.api.libs.json.JsObject
import play.api.libs.json.Json
import play.api.libs.json.Json.toJsFieldJsValueWrapper


/**
  * Base environment context providing access to all resources and services to 
  * engines.  Specific [[EvalEngine evaluation engines]] can 
  * define and use their own specific context by extending this one. 
  * 
  * Access to page scope data is provided through a dataScope method.
  * 
  * @author Branko Juric
  */
class EnvContext(scopes: ScopedDataStack) extends LazyLogging {
  
  /** Map of step definitions keyed by callable expression name. */
  private var stepDefs = Map[String, Scenario]()
  
  /** List of current attachments (name-file pairs). */
  private[eval] var attachments: List[(String, File)] = Nil
  
  /** Provides access to the global feature scope. */
  def featureScope = scopes.featureScope
  
  /**
    * Closes any resources associated with the evaluation context. This implementation
    * does nothing (but subclasses can override).
    */
  def close() { }
  
  /** Resets the current context but does not close it so it can be reused. */
  def reset() {
    scopes.reset()
    stepDefs = Map[String, Scenario]()
    resetAttachments
  }
  
  /** Returns the current state of all scoped attributes as a Json object. */  
  def json: JsObject = scopes.json
  
  /** Returns the current visible attributes as a Json object. */  
  def visibleJson: JsObject = scopes.visibleJson
  
  /**
    * Gets a named data scope (creates it if it does not exist)
    * 
    * @param name the name of the data scope to get (or create and get)
    */
  def addScope(name: String) = scopes.addScope(name)
  
  /**
    * Adds a step definition to the context.
    * 
    * @param stepDef the step definition to add
    */
  def addStepDef(stepDef: Scenario) {
    stepDefs += ((stepDef.name, stepDef)) 
  }
  
  /**
    * Gets the executable step definition for the given expression (if there is
    * one).
    * 
    * @param expression the expression to match
    * @return the step definition if a match is found; false otherwise
    */
  def getStepDef(expression: String): Option[Scenario] = 
    stepDefs.get(expression) collect { case Scenario(tags, expression, _, steps) => 
      Scenario(tags, expression, steps map { step => 
        Step(step.keyword, step.expression) tap { _.pos = step.pos }
      }) 
    }
  
  /**
    * Fail handler.
    * 
    * @param failed the failed status
    */
  final def fail(failure: Failed): Unit = { 
    attachments = createErrorAttachments(failure)
    logger.error(Json.prettyPrint(this.visibleJson))
    logger.error(failure.error.getMessage())
    logger.debug(s"Exception: ", failure.error)
  }
  
  /**
    * Adds error attachments to the current context.
    * 
    * @param failed the failed status
    */
  def createErrorAttachments(failure: Failed): List[(String, File)] = List( 
    ("Error details", 
      File.createTempFile("error-details-", ".txt") tap { f =>
        f.deleteOnExit()
        f.writeText(failure.error.writeStackTrace())
      }
    ), 
    ("Environment (all)", 
      File.createTempFile("env-all-", ".txt") tap { f =>
        f.deleteOnExit()
        f.writeText(Json.prettyPrint(this.json))
      }
    ),
    ("Environment (visible)", 
      File.createTempFile("env-visible-", ".txt") tap { f =>
        f.deleteOnExit()
        f.writeText(Json.prettyPrint(this.visibleJson))
      }
    )
  )
  
  /**
    * Adds an attachment to the current context.
    * 
    * @param attachment the attachment (name-file pair) to add
    * @param file the attachment file
    */
  def addAttachment(attachment: (String, File)): Unit = {
    attachments = attachment :: attachments.filter(_._1 != attachment._1)
  } 
  
  /** Resets/clears current attachments. */
  private[eval] def resetAttachments() {
    attachments = Nil
  }
  
  /**
    * Can be overridden by subclasses to parse and resolve the given step 
    * before it is evaluated. This implementation simply returns the step 
    * as is.
    * 
    * @param step the step to resolve
    * @return the resolved step
    */
  def resolve(step: Step): Step = step
}

/** Merges two contexts into one. */
class HybridEnvContext[A <: EnvContext, B <: EnvContext](val envA: A, val envB: B, val scopes: ScopedDataStack) extends EnvContext(scopes) {
  override def close() {
    try {
      envB.close()
    } finally {
      envA.close()
    }
  }
}
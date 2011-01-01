package org.apache.activemq.apollo.broker.security

/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License") you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.security.Principal
import java.util.Properties
import javax.security.auth.Subject
import javax.security.auth.callback.CallbackHandler
import javax.security.auth.login.LoginException
import javax.security.auth.spi.LoginModule

import org.apache.activemq.jaas.GroupPrincipal
import org.apache.activemq.jaas.UserPrincipal
import java.{util => ju}
import org.apache.activemq.apollo.util.{FileSupport, Log}
import FileSupport._
import java.util.regex.Pattern

object FileGroupLoginModule extends Log {
  val LOGIN_CONFIG = "java.security.auth.login.config"
  val FILE_OPTION = "file"
  val MATCH_OPTION = "match"
  val SEPARATOR_OPTION = "separator"
}

/**
 * <p>
 * This login module adds additional GroupPrincipals to the
 * subject based on existing principle already associated with the principal
 * and a groups file.
 * </p>
 *
 * @author <a href="http://hiramchirino.com">Hiram Chirino</a>
 */
class FileGroupLoginModule extends LoginModule {

  import FileGroupLoginModule._

  private var separator: String = _
  private var match_kind: String = _
  private var subject: Subject = _
  private var file: File = _

  private val groups = new Properties()
  private val principals = new ju.HashSet[Principal]()

  def initialize(subject: Subject, callback_handler: CallbackHandler, shared_state: ju.Map[String, _], options: ju.Map[String, _]): Unit = {
    this.subject = subject
    val base_dir = if (System.getProperty(LOGIN_CONFIG) != null) {
      new File(System.getProperty(LOGIN_CONFIG)).getParentFile()
    } else {
      new File(".")
    }

    match_kind = Option(options.get(MATCH_OPTION)).
                  map(_.asInstanceOf[String]).
                  getOrElse(classOf[UserPrincipal].getName)

    separator = Option(options.get(SEPARATOR_OPTION)).
                  map(_.asInstanceOf[String]).
                  getOrElse("|")

    file = new File(base_dir, options.get(FILE_OPTION).asInstanceOf[String])
    debug("Initialized file=%s, match=%s", file, match_kind)
  }

  def login: Boolean = {
    try {
      groups.clear
      using( new FileInputStream(file) ) { in=>
        groups.load(in)
      }
    } catch {
      case ioe: IOException => throw new LoginException("Unable to load group properties file " + file)
    }
    false
  }

  def commit: Boolean = {

    import collection.JavaConversions._
    val principles = subject.getPrincipals.filter(_.getClass.getName == match_kind).map(_.getName)

    val en = groups.keys()
    while (en.hasMoreElements()) {
      val group_name = en.nextElement().asInstanceOf[String]
      val users = groups.getProperty(group_name).split(Pattern.quote(separator)).map(_.trim)
      users.foreach { x =>
        if ( principles.contains(x) ) {
          principals.add(new GroupPrincipal(group_name))
        }
      }
    }

    subject.getPrincipals().addAll(principals)

    debug("commit")
    return true
  }

  def abort: Boolean = {
    debug("abort")
    return true
  }

  def logout: Boolean = {
    subject.getPrincipals().removeAll(principals)
    principals.clear
    debug("logout")
    return true
  }


}
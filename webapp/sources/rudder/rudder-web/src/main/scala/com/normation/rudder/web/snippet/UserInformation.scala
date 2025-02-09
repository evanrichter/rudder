/*
*************************************************************************************
* Copyright 2011 Normation SAS
*************************************************************************************
*
* This file is part of Rudder.
*
* Rudder is free software: you can redistribute it and/or modify
* it under the terms of the GNU General Public License as published by
* the Free Software Foundation, either version 3 of the License, or
* (at your option) any later version.
*
* In accordance with the terms of section 7 (7. Additional Terms.) of
* the GNU General Public License version 3, the copyright holders add
* the following Additional permissions:
* Notwithstanding to the terms of section 5 (5. Conveying Modified Source
* Versions) and 6 (6. Conveying Non-Source Forms.) of the GNU General
* Public License version 3, when you create a Related Module, this
* Related Module is not considered as a part of the work and may be
* distributed under the license agreement of your choice.
* A "Related Module" means a set of sources files including their
* documentation that, without modification of the Source Code, enables
* supplementary functions or services in addition to those offered by
* the Software.
*
* Rudder is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
* GNU General Public License for more details.
*
* You should have received a copy of the GNU General Public License
* along with Rudder.  If not, see <http://www.gnu.org/licenses/>.

*
*************************************************************************************
*/

package com.normation.rudder.web.snippet

import net.liftweb.common._
import net.liftweb.http._
import net.liftweb.http.js._
import net.liftweb.util.Helpers._
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.core.userdetails.UserDetails
import com.normation.rudder.domain.eventlog.LogoutEventLog
import com.normation.eventlog.EventActor
import com.normation.rudder.web.services.CurrentUser
import com.normation.eventlog.EventLogDetails
import com.normation.eventlog.EventLog
import com.normation.eventlog.ModificationId
import bootstrap.liftweb.RudderConfig
import com.normation.plugins.DefaultExtendableSnippet
import com.normation.rudder.domain.logger.ApplicationLogger
import com.normation.zio._

import scala.xml.NodeSeq

class UserInformation extends DispatchSnippet with DefaultExtendableSnippet[UserInformation] {

  private[this] val eventLogger = RudderConfig.eventLogRepository
  private[this] val uuidGen     = RudderConfig.stringUuidGenerator

  override def mainDispatch: Map[String, NodeSeq => NodeSeq] = Map(
      "userCredentials" -> ((xml:NodeSeq) => userCredentials(xml))
    , "logout"          -> ((xml:NodeSeq) => logout(xml))
  )

  def userCredentials = {
    CurrentUser.get match {
      case Some(u) =>  "#openerAccount" #> u.getUsername
      case None =>
        S.session.foreach { session =>
          SecurityContextHolder.clearContext()
          session.destroySession()
        }
        "#user-menu *" #> <p class="error">Error when trying to fetch user details.</p>
    }
  }

  def logout = {
    "*" #> SHtml.ajaxButton("Log out", { () =>
      S.session match {
        case Full(session) => //we have a session, try to know who is login out
          SecurityContextHolder.getContext.getAuthentication match {
            case null => //impossible to know who is login out
              ApplicationLogger.debug("Logout called for a null authentication, can not log user out")
            case auth => auth.getPrincipal() match {
              case u:UserDetails =>
                eventLogger.saveEventLog(
                    ModificationId(uuidGen.newUuid)
                  , LogoutEventLog(
                        EventLogDetails(
                            modificationId = None
                          , principal = EventActor(u.getUsername)
                          , details = EventLog.emptyDetails
                          , reason = None
                        )
                    )
                ).runNowLogError(err => ApplicationLogger.error( s"Error when saving user loggin event log result: ${err.fullMsg}"))

              case x => //impossible to know who is login out
                ApplicationLogger.debug("Logout called with unexpected UserDetails, can not log user logout. Details: " + x)
            }
          }
          SecurityContextHolder.clearContext()
          session.destroySession()
        case e:EmptyBox => //no session ? Strange, but ok, nobody is login
          ApplicationLogger.debug("Logout called for a non existing session, nothing more done")
      }
      JsCmds.RedirectTo("/")
    }, ("class", "btn btn-danger"))
  }

}

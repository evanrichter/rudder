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

package com.normation.rudder.services.policies

import net.liftweb.common.EmptyBox
import com.normation.rudder.services.nodes.NodeInfoService
import com.normation.rudder.repository.LicenseRepository
import com.normation.cfclerk.domain._
import com.normation.rudder.domain.nodes.NodeInfo
import net.liftweb.common.Box
import com.normation.rudder.domain.nodes.NodeInfo
import com.normation.inventory.domain.NodeId
import com.normation.inventory.domain._
import net.liftweb.common._
import org.slf4j.{ Logger, LoggerFactory }
import com.normation.rudder.exceptions.LicenseException
import com.normation.cfclerk.services.SystemVariableSpecService
import com.normation.rudder.repository.FullActiveTechniqueCategory
import com.normation.rudder.repository.FullNodeGroupCategory
import com.normation.rudder.domain.policies.RuleId
import com.normation.rudder.domain.policies.Rule
import com.normation.rudder.domain.Constants
import com.normation.rudder.services.servers.PolicyServerManagementService
import com.normation.rudder.reports.ComplianceMode
import com.normation.rudder.reports.FullCompliance
import com.normation.rudder.reports.ChangesOnly
import com.normation.rudder.reports.AgentRunInterval
import com.normation.rudder.reports.SyslogProtocol
import com.normation.rudder.domain.licenses.NovaLicense
import com.normation.rudder.reports.AgentRunIntervalService
import com.normation.rudder.reports.ComplianceModeService
import com.normation.rudder.repository.FullNodeGroupCategory
import com.normation.rudder.domain.policies.GroupTarget
import com.normation.rudder.domain.policies.RuleTarget

trait SystemVariableService {
  def getGlobalSystemVariables(globalAgentRun: AgentRunInterval):  Box[Map[String, Variable]]

  def getSystemVariables(
      nodeInfo              : NodeInfo
    , allNodeInfos          : Map[NodeId, NodeInfo]
    , allGroups             : FullNodeGroupCategory
    , allLicences           : Map[NodeId, NovaLicense]
    , globalSystemVariables : Map[String, Variable]
    , globalAgentRun        : AgentRunInterval
    , globalComplianceMode  : ComplianceMode  ) : Box[Map[String, Variable]]
}

final case class RudderServerRole(
    val name       : String
  , val configValue: String
)

final case class ResolvedRudderServerRole(
    val name       : String
  , val configValue: Option[Iterable[String]]
)

class SystemVariableServiceImpl(
    systemVariableSpecService    : SystemVariableSpecService
  , policyServerManagementService: PolicyServerManagementService
  // Variables definitions
  , toolsFolder              : String
  , cmdbEndPoint             : String
  , communityPort            : Int
  , sharedFilesFolder        : String
  , webdavUser               : String
  , webdavPassword           : String
  , reportsDbUri             : String
  , reportsDbUser            : String
  , syslogPort               : Int
  , configurationRepository  : String
  , serverRoles              : Seq[RudderServerRole]
  //denybadclocks and skipIdentify are runtime properties
  , getDenyBadClocks: () => Box[Boolean]
  , getSkipIdentify : () => Box[Boolean]
  // TTLs are runtime properties too
  , getModifiedFilesTtl             : () => Box[Int]
  , getCfengineOutputsTtl           : () => Box[Int]
  , getStoreAllCentralizedLogsInFile: () => Box[Boolean]
  , getSendMetrics                  : () => Box[Option[Boolean]]
  , getSyslogProtocol               : () => Box[SyslogProtocol]
) extends SystemVariableService with Loggable {

  //get the Rudder reports DB (postgres) database name from URI
  val reportsDbName = {
    reportsDbUri.split("""/""").toSeq.lastOption.getOrElse(throw new IllegalArgumentException(
        s"The JDBC URI configure for property 'rudder.jdbc.url' is malformed and should ends by /BASENAME: ${reportsDbUri}")
    )
  }


  val varToolsFolder = systemVariableSpecService.get("TOOLS_FOLDER").toVariable().copyWithSavedValue(toolsFolder)
  val varCmdbEndpoint = systemVariableSpecService.get("CMDBENDPOINT").toVariable().copyWithSavedValue(cmdbEndPoint)
  val varWebdavUser = systemVariableSpecService.get("DAVUSER").toVariable().copyWithSavedValue(webdavUser)
  val varWebdavPassword = systemVariableSpecService.get("DAVPASSWORD").toVariable().copyWithSavedValue(webdavPassword)
  val varSharedFilesFolder = systemVariableSpecService.get("SHARED_FILES_FOLDER").toVariable().copyWithSavedValue(sharedFilesFolder)
  val varCommunityPort = systemVariableSpecService.get("COMMUNITYPORT").toVariable().copyWithSavedValue(communityPort.toString)
  val syslogPortConfig = systemVariableSpecService.get("SYSLOGPORT").toVariable().copyWithSavedValue(syslogPort.toString)
  val configurationRepositoryFolder = systemVariableSpecService.get("CONFIGURATION_REPOSITORY_FOLDER").toVariable().copyWithSavedValue(configurationRepository)

  // Compute the values for rudderServerRoleLdap, rudderServerRoleDb and rudderServerRoleRelayTop
  // if autodetect, then it is not defined, otherwise we parse it
  val AUTODETECT_KEYWORD="autodetect"
  def parseRoleContent(value: String) : Option[Iterable[String]] = {
    value match {
      case AUTODETECT_KEYWORD => None
      case _ => Some(value.split(","))
    }
  }

  lazy val defaultServerRoles = serverRoles.map( x => ResolvedRudderServerRole(x.name, parseRoleContent(x.configValue)))

  // compute all the global system variable (so that need to be computed only once in a deployment)

  def getGlobalSystemVariables(globalAgentRun: AgentRunInterval):  Box[Map[String, Variable]] = {
    logger.trace("Preparing the global system variables")
    val denyBadClocks = getProp("DENYBADCLOCKS", getDenyBadClocks)
    val skipIdentify = getProp("SKIPIDENTIFY", getSkipIdentify)
    val modifiedFilesTtl = getProp("MODIFIED_FILES_TTL", getModifiedFilesTtl)
    val cfengineOutputsTtl = getProp("CFENGINE_OUTPUTS_TTL", getCfengineOutputsTtl)
    val reportProtocol = getProp("RUDDER_SYSLOG_PROTOCOL", () => getSyslogProtocol().map(_.value))

    val sendMetricsValue = if (getSendMetrics().getOrElse(None).getOrElse(false)) {
      "yes"
    } else {
      "no"
    }
    val varSendMetrics = systemVariableSpecService.get("SEND_METRICS").toVariable(Seq(sendMetricsValue))

    val storeAllCentralizedLogsInFile = getProp("STORE_ALL_CENTRALIZED_LOGS_IN_FILE", getStoreAllCentralizedLogsInFile)

    for {
      schedule       <- ComputeSchedule.computeSchedule(globalAgentRun.startHour, globalAgentRun.startMinute, globalAgentRun.interval) ?~! "Could not compute the run schedule"
    } yield {
      val varAgentRunInterval  = systemVariableSpecService.get("AGENT_RUN_INTERVAL").toVariable(Seq(globalAgentRun.interval.toString))
      val varAgentRunSplayTime = systemVariableSpecService.get("AGENT_RUN_SPLAYTIME").toVariable(Seq(globalAgentRun.splaytime.toString))
      val varAgentRunSchedule = systemVariableSpecService.get("AGENT_RUN_SCHEDULE").toVariable(Seq(schedule))
      logger.trace("Global system variables done")
      val vars =
        varToolsFolder ::
        varCmdbEndpoint ::
        varSharedFilesFolder ::
        varCommunityPort ::
        varWebdavUser  ::
        varWebdavPassword ::
        syslogPortConfig ::
        configurationRepositoryFolder ::
        denyBadClocks ::
        skipIdentify ::
        varAgentRunInterval ::
        varAgentRunSchedule ::
        varAgentRunSplayTime  ::
        modifiedFilesTtl ::
        cfengineOutputsTtl ::
        storeAllCentralizedLogsInFile ::
        varSendMetrics ::
        reportProtocol ::
        Nil
      vars.map(v => (v.spec.name,v)).toMap
    }
  }

  // allNodeInfos has to contain ALL the node info (those of every node within Rudder)
  // for this method to work properly

  // The global system variables are computed before (in the method up there), and
  // can be overriden by some node specific parameters (especially, the schedule for
  // policy servers)
  def getSystemVariables(
        nodeInfo              : NodeInfo
      , allNodeInfos          : Map[NodeId, NodeInfo]
      , allGroups             : FullNodeGroupCategory
      , allLicenses           : Map[NodeId, NovaLicense]
      , globalSystemVariables : Map[String, Variable]
      , globalAgentRun        : AgentRunInterval
      , globalComplianceMode  : ComplianceMode
  ): Box[Map[String, Variable]] = {

    logger.trace("Preparing the system variables for node %s".format(nodeInfo.id.value))

    // Set the roles of the nodes
    val nodeConfigurationRoles = collection.mutable.Set[ServerRole]() ++ nodeInfo.serverRoles

    // Define the mapping of roles/hostnames, only if the node has a role
    val varRoleMappingValue = if (nodeConfigurationRoles.size > 0) {
      val allNodeInfosSet = allNodeInfos.values.toSet

      val roles = defaultServerRoles.map { case ResolvedRudderServerRole(name, optValue) =>
        val nodeValue = optValue match {
          case Some(seq) => seq
          case None      => getNodesWithRole(allNodeInfosSet, ServerRole(name))
        }
        writeNodesWithRole(nodeValue, name)
      }

      //build the final string
      (""/:roles) { (x,y) => x + y }
    } else {
      ""
    }

    val varRudderServerRole = systemVariableSpecService.get("RUDDER_SERVER_ROLES").toVariable().copyWithSavedValue(varRoleMappingValue)

    // we need to know the mapping between policy servers and their children - do it one time for all nodes.
    val childrenByPolicyServer = allNodeInfos.values.toList.groupBy( _.policyServerId )


    if (nodeInfo.isPolicyServer) {
      nodeConfigurationRoles.add(ServerRole("policy_server"))
      if (nodeInfo.id == nodeInfo.policyServerId) {
        nodeConfigurationRoles.add(ServerRole("root_server"))
      }
    }

    val varNodeRoleValue = if (nodeConfigurationRoles.size > 0) {
      "  classes: \n" + nodeConfigurationRoles.map(x => "    \"" + x.value + "\" expression => \"any\";").mkString("\n")
    } else {
      "# This node doesn't have any specific role"
    }

    val varNodeRole = systemVariableSpecService.get("NODEROLE").toVariable().copyWithSavedValue(varNodeRoleValue)

    // Set the licences for the Nova
    val varLicensesPaidValue = if (nodeInfo.agentsName.contains(NOVA_AGENT)) {
      allLicenses.get(nodeInfo.policyServerId) match {
        case None =>
          logger.info(s"Caution, the policy server '${nodeInfo.policyServerId.value}' does not have a registered Nova license. You will have to get one if you run more than 25 nodes")
          //that's the default value
          "25"
        case Some(x) => x.licenseNumber.toString
      }
    } else {
      "1"
    }

    val authorizedNetworks = policyServerManagementService.getAuthorizedNetworks(nodeInfo.id) match {
      case eb:EmptyBox =>
        //log ?
        Seq()
      case Full(nets) => nets
    }

    val varAllowedNetworks = systemVariableSpecService.get("AUTHORIZED_NETWORKS").toVariable(authorizedNetworks)

    val agentRunParams =
      if (nodeInfo.isPolicyServer) {
        val policyServerSchedule = """ "Min00", "Min05", "Min10", "Min15", "Min20", "Min25", "Min30", "Min35", "Min40", "Min45", "Min50", "Min55" """
        Full((AgentRunInterval(Some(false), 5, 0, 0, 0), policyServerSchedule))
      } else {
        val runInterval = nodeInfo.nodeReportingConfiguration.agentRunInterval match {
          case Some(nodeRunInterval)  if nodeRunInterval.overrides.getOrElse(false) =>
            nodeRunInterval
          case _ =>
            globalAgentRun
        }
        for {
          schedule <- ComputeSchedule.computeSchedule(
                              runInterval.startHour
                            , runInterval.startMinute
                            , runInterval.interval
                          ) ?~! s"Could not compute the run schedule for node ${nodeInfo.id.value}"
            } yield {
              ( runInterval, schedule )
        }
      }

    val heartBeatFrequency = {
      if (nodeInfo.isPolicyServer) {
        // A policy server is always sending heartbeat
        1
      } else {
        globalComplianceMode.mode match {
          case ChangesOnly =>
            nodeInfo.nodeReportingConfiguration.heartbeatConfiguration match {
              // It overrides! use it to compute the new heartbeatInterval
              case Some(heartbeatConf) if heartbeatConf.overrides =>
                heartbeatConf.heartbeatPeriod
              case _ =>
                globalComplianceMode.heartbeatPeriod
            }
          case _ =>
            1
        }
      }
    }

    val AgentRunVariables = ( agentRunParams.map {
      case (runInterval,schedule) =>

        // The heartbeat should be strictly shorter than the run execution, otherwise they may be skipped
        val heartbeat = runInterval.interval * heartBeatFrequency - 1
        val vars = {
          systemVariableSpecService.get("AGENT_RUN_INTERVAL").toVariable().copyWithSavedValue(runInterval.interval.toString) ::
          systemVariableSpecService.get("AGENT_RUN_SPLAYTIME").toVariable().copyWithSavedValue(runInterval.splaytime.toString)  ::
          systemVariableSpecService.get("AGENT_RUN_SCHEDULE").toVariable().copyWithSavedValue(schedule) ::
          systemVariableSpecService.get("RUDDER_HEARTBEAT_INTERVAL").toVariable(Seq(heartbeat.toString)) ::
          systemVariableSpecService.get("RUDDER_REPORT_MODE").toVariable(Seq(globalComplianceMode.name)) ::
          Nil
        }
        vars.map(v => v.spec.name -> v ).toMap
    } )

    // If we are facing a policy server, we have to allow each child to connect, plus the policy parent,
    // else it's only the policy server
    val policyServerVars = if (nodeInfo.isPolicyServer) {

      // Find the "policy children" of this policy server
      // thanks to the allNodeInfos, this is super easy
      //IT IS VERY IMPORTANT TO SORT SYSTEM VARIABLE HERE: see ticket #4859
      val children = childrenByPolicyServer.getOrElse(nodeInfo.id, Nil).sortBy( _.id.value )

      val varManagedNodes      = systemVariableSpecService.get("MANAGED_NODES_NAME" ).toVariable(children.map(_.hostname))
      val varManagedNodesId    = systemVariableSpecService.get("MANAGED_NODES_ID"   ).toVariable(children.map(_.id.value))
      val varManagedNodesKey   = systemVariableSpecService.get("MANAGED_NODES_KEY"  ).toVariable(children.map(n => s"MD5=${n.cfengineKeyHash}"))
      //IT IS VERY IMPORTANT TO SORT SYSTEM VARIABLE HERE: see ticket #4859
      val varManagedNodesAdmin = systemVariableSpecService.get("MANAGED_NODES_ADMIN").toVariable(children.map(_.localAdministratorAccountName).distinct.sorted)

      //IT IS VERY IMPORTANT TO SORT SYSTEM VARIABLE HERE: see ticket #4859
      val varManagedNodesIp = systemVariableSpecService.get("MANAGED_NODES_IP"      ).toVariable(children.flatMap(_.ips).distinct.sorted)

      // same kind of variable but for ALL cildrens, not only direct one:
      val allChildren = {
         //utility to add children of a list of nodes
        def addWithSubChildren(nodes: List[NodeInfo]): List[NodeInfo] = {
          nodes.flatMap(n =>
            n :: {
              childrenByPolicyServer.get(n.id) match {
                case None           => Nil
                case Some(children) =>
                  // Root server is its own policy server, we must remove it
                  val sanitizedChildren = children.filterNot( x => x.id.value == "root" )
                  addWithSubChildren(sanitizedChildren)
              }
            }
          )
        }
        addWithSubChildren(children)
      }

      val varSubNodesName    = systemVariableSpecService.get("SUB_NODES_NAME"   ).toVariable(allChildren.map(_.hostname))
      val varSubNodesId      = systemVariableSpecService.get("SUB_NODES_ID"     ).toVariable(allChildren.map(_.id.value))
      val varSubNodesServer  = systemVariableSpecService.get("SUB_NODES_SERVER" ).toVariable(allChildren.map(_.policyServerId.value))
      val varSubNodesKeyhash = systemVariableSpecService.get("SUB_NODES_KEYHASH").toVariable(allChildren.map(n => s"sha256:${n.sha256KeyHash}"))


      //Reports DB (postgres) DB name and DB user
      val varReportsDBname = systemVariableSpecService.get("RUDDER_REPORTS_DB_NAME").toVariable(Seq(reportsDbName))
      val varReportsDBuser = systemVariableSpecService.get("RUDDER_REPORTS_DB_USER").toVariable(Seq(reportsDbUser))

      // the schedule must be the default one for policy server

      Seq(
          varManagedNodes
        , varManagedNodesId
        , varManagedNodesAdmin
        , varManagedNodesIp
        , varManagedNodesKey
        , varReportsDBname
        , varReportsDBuser
      ) map (x => (x.spec.name, x))
    } else {
      Map()
    }

    logger.trace("System variables for node %s done".format(nodeInfo.id.value))

    /*
     * RUDDER_NODE_CONFIG_ID is a very special system variable:
     * it must not be used to assess node config stability from
     * run to run.
     * So we set it to a default value and handle it specialy in
     * Cf3PromisesFileWriterServiceImpl#prepareRulesForAgents
     */
    val varNodeConfigVersion = systemVariableSpecService.get("RUDDER_NODE_CONFIG_ID").toVariable(Seq("DUMMY NODE CONFIG VERSION"))


    /*
     * RUDDER_NODE_GROUPS_VAR is an array of group_uuid -> group_name for the node
     * RUDDER_NODE_GROUPS_CLASSE are pairs of group_UUID, group_NORMALIZED_NAME,
     * for ex if node belongs to group:
     * (id: 64f85ba8-39c7-418a-a099-24c2c2909dfd ; name: "Serveurs pre-prod")
     * we will have the following classes:
     *   - group_64f85ba8_39c7_418a_a099_24c2c2909dfd
     *   - group_serveurs_pre_prod
     * and vars:
     *   - "by_uuid[64f85ba8-39c7-418a-a099-24c2c2909dfd]" string => "Serveurs pre-prod"
     *     with a meta: { "inventory", "attribute_name=rudder_groups" }
     */
    //build the list of nodeId -> names, taking care of special nodeIds for special target
    val nodeGroups = allGroups.getTarget(nodeInfo).map { case(target, info) =>
      val id = info.target.target match {
        case GroupTarget(id) => id.value
        case t => t.target
      }
      (id, info.name)
    }
    val nodeMaxString = if(nodeGroups.isEmpty) 0 else nodeGroups.flatMap { case (a,b) => a.size :: b.size :: Nil }.max
    val stringNodeGroupsVars = if(nodeGroups.isEmpty) {
      ""
    } else {
      nodeGroups.map { case (id, name) =>
        s""""by_uuid[${id}]" ${" "*(nodeMaxString-id.size)} string => "${name}",\n""" +
        s"""            ${" "*(nodeMaxString)        }   meta => { "inventory", "attribute_name=rudder_groups" };"""
      }.mkString("\n")
    }
    val stringNodeGroupsClasses = if(nodeGroups.isEmpty) {
      ""
    } else {
      nodeGroups.flatMap { case (id, name) =>
        (  s""""${RuleTarget.toCFEngineClassName(id  )}" ${" "*(nodeMaxString-  id.size)} expression => "any",\n""" +
           s"""             ${" "*(nodeMaxString)}   meta => { "inventory", "attribute_name=rudder_groups" };"""
        :: s""""${RuleTarget.toCFEngineClassName(name)}" ${" "*(nodeMaxString-name.size)} expression => "any",\n""" +
           s"""             ${" "*(nodeMaxString)}   meta => { "inventory", "attribute_name=rudder_groups" };"""
        :: Nil
        )
      }.mkString("\n")
    }
    val varNodeGroups = systemVariableSpecService.get("RUDDER_NODE_GROUPS_VARS").toVariable(Seq(stringNodeGroupsVars))
    val varNodeGroupsClasses = systemVariableSpecService.get("RUDDER_NODE_GROUPS_CLASSES").toVariable(Seq(stringNodeGroupsClasses))

    val baseVariables = {
      Seq(
          varNodeRole
        , varAllowedNetworks
        , varRudderServerRole
        , varNodeConfigVersion
        , varNodeGroups
        , varNodeGroupsClasses
      ) map (x => (x.spec.name, x))
    }

    val variables = globalSystemVariables ++ baseVariables ++ policyServerVars

    AgentRunVariables match {
      case Full(runValues)  =>
        Full(variables ++ runValues)
      case Empty =>
        Full(variables)
      case fail: Failure =>
        fail
    }

  }

  // Fetch the Set of node hostnames having specific role
  private[this] def getNodesWithRole(
      allNodeInfos  : Set[NodeInfo]
    , role          : ServerRole
  ) : Set[String] = {
    allNodeInfos.filter(x => x.serverRoles.contains(role)).map(_.hostname)
  }

  // Formating of the roles
  private[this] def writeNodesWithRole(
      nodesWithRole: Iterable[String]
    , roleName     : String
  ) : String = {
    nodesWithRole.size match {
      case 0 => "" // no string, no role
      case _ => s"${roleName}:${nodesWithRole.mkString(",")}\n"
    }
  }

  //obtaining variable values from (failable) properties
  private[this] def getProp[T](specName: String, getter: () => Box[T]): SystemVariable = {
      //try to get the user configured value, else log an error and use the default value.
      val variable = systemVariableSpecService.get(specName).toVariable()

      getter() match {
        case Full(value) =>
          variable.copyWithSavedValue(value.toString)
        case eb: EmptyBox =>
          val e = eb ?~! s"Error when trying to get the value configured by the user for system variable '${specName}'"
          logger.error(e.messageChain)
          e.rootExceptionCause.foreach { ex =>
            logger.error("Root exception cause was:", ex)
          }
          variable
      }
    }
}

object ComputeSchedule {
  def computeSchedule(
      startHour        : Int
    , startMinute      : Int
    , executionInterval: Int
  ): Box[String] = {

    val minutesFreq = executionInterval % 60
    val hoursFreq: Int = executionInterval / 60

    (minutesFreq, hoursFreq) match {
      case (m, h) if m > 0 && h > 0 => Failure(s"Agent execution interval can only be defined as minutes (less than 60) or complete hours, (${h} hours ${m} minutes is not supported)")
      case (m, h) if h == 0 =>
        // two cases, hour is 0, then only minutes

        // let's modulate startMinutes by minutes
        val actualStartMinute = startMinute % minutesFreq
        val mins = Range(actualStartMinute, 60, minutesFreq) // range doesn't return the end range
        //val mins = for ( min <- 0 to 59; if ((min%minutesFreq) == actualStartMinute) ) yield { min }
        Full(mins.map("\"Min" + "%02d".format(_) + "\"").mkString(", "))

      case _ =>
        // hour is not 0, then we don't have minutes
        val actualStartHour = startHour % hoursFreq
        val hours = Range(actualStartHour, 24, hoursFreq)
        val minutesFormat = "Min" + "%02d".format(startMinute)
        Full(hours.map("\"Hr" + "%02d".format(_) + "." + minutesFormat + "\"").mkString(", "))
    }

  }

}

package com.galacticfog.gestalt.dcos

import java.util.UUID
import javax.inject.Inject

import com.galacticfog.gestalt.dcos.marathon._
import org.apache.mesos.Protos
import org.apache.mesos.Protos.Environment.Variable
import org.apache.mesos.Protos._
import play.api.Configuration
import play.api.libs.json.JsValue

case class PortSpec(number: Int, name: String, labels: Map[String,String])
case class HealthCheck(portIndex: Int, protocol: String, path: String)

case class AppSpec(name: String,
                   image: String,
                   numInstances: Int = 1,
                   network: Protos.ContainerInfo.DockerInfo.Network,
                   ports: Option[Seq[PortSpec]] = None,
                   dockerParameters: Seq[KeyValuePair] = Seq.empty,
                   cpus: Double,
                   mem: Int,
                   env: Map[String,String] = Map.empty,
                   labels: Map[String,String ] = Map.empty,
                   args: Option[Seq[String]] = None,
                   cmd: Option[String] = None,
                   volumes: Option[Seq[marathon.Volume]] = None,
                   healthChecks: Seq[HealthCheck] = Seq.empty,
                   readinessCheck: Option[MarathonReadinessCheck] = None)


case class GlobalDBConfig(hostname: String,
                          port: Int,
                          username: String,
                          password: String,
                          prefix: String)

case object GlobalDBConfig {
  def apply(global: JsValue): GlobalDBConfig = GlobalDBConfig(
    hostname = (global \ "database" \ "hostname").asOpt[String].getOrElse("data.gestalt.marathon.mesos"),
    port = (global \ "database" \ "port").asOpt[Int].getOrElse(5432),
    username = (global \ "database" \ "username").asOpt[String].getOrElse("gestaltdev"),
    password = (global \ "database" \ "password").asOpt[String].getOrElse("letmein"),
    prefix = (global \ "database" \ "prefix").asOpt[String].getOrElse("gestalt-")
  )
}

class GestaltTaskFactory @Inject() (config: Configuration) {

  def env(name: String, default: String): String = {
    scala.util.Properties.envOrElse(name, default)
  }

  val RABBIT_EXCHANGE = "policy-exchange"

  val VIP = config.getString("service.vip") getOrElse "10.10.10.10"

  val TLD = config.getString("marathon.tld")

  val appGroup = config.getString("marathon.app-group").getOrElse(GestaltTaskFactory.DEFAULT_APP_GROUP).stripPrefix("/").stripSuffix("/")

  val provisionDB = config.getBoolean("database.provision") getOrElse true
  val provisionedDBSize = config.getInt("database.provisioned-size") getOrElse 100

  val dockerImages: Map[String,String] = Map(
    "data" -> env("GESTALT_DATA_IMG","galacticfog.artifactoryonline.com/gestalt-data:latest"),
    "rabbit" -> env("GESTALT_RABBIT_IMG","rabbitmq:3-management"),
    "kong" -> env("GESTALT_KONG_IMG","galacticfog/kong:0.8.0"),
    "security" -> env("GESTALT_SECURITY_IMG","galacticfog.artifactoryonline.com/gestalt-security:2.2.5-SNAPSHOT-ec05ef5a"),
    "meta" -> env("GESTALT_META_IMG","galacticfog.artifactoryonline.com/gestalt-meta:0.3.3-SNAPSHOT-440a7727"),
    "policy" -> env("GESTALT_POLICY_IMG","galacticfog.artifactoryonline.com/gestalt-policy:0.0.2-SNAPSHOT-d7805889"),
    "lambda" -> env("GESTALT_LAMBDA_IMG","galacticfog.artifactoryonline.com/gestalt-lambda:1.0.3-SNAPSHOT-2de5aaf0"),
    "api-gateway" -> env("GESTALT_API_GATEWAY_IMG","galacticfog.artifactoryonline.com/gestalt-api-gateway:1.0.3-SNAPSHOT-ebf0f14b"),
    "api-proxy" -> env("GESTALT_API_PROXY_IMG","galacticfog.artifactoryonline.com/gestalt-api-proxy:0.5.9-d21d36cd"),
    "ui" -> env("GESTALT_UI_IMG","galacticfog.artifactoryonline.com/gestalt-ui:0.8.1-22d6bf3f")
  )

  def getVhostLabels(svcname: String): Map[String,String] = {
    TLD match {
      case Some(tld) => Map(
        "HAPROXY_0_VHOST" -> s"${svcname}.${tld}",
        "HAPROXY_GROUP" -> "external"
      )
      case None => Map.empty[String,String]
    }
  }

  def dest(svc: String) = s"${VIP}:${ports(svc)}"

  val ports = Map(
    "data"         ->   "5432",
    "rabbit"       ->   "5672",
    "rabbit-http" ->   "15672",
    "kong-gateway" ->   "8000",
    "kong-service" ->   "8001",
    "security"     ->   "9455",
    "api-gateway"  ->   "6473",
    "lambda"       ->   "1111",
    "meta"         ->  "14374",
    "api-proxy"    ->     "81",
    "ui"           ->     "80",
    "policy"       ->   "9999"
  )

  import GestaltTaskFactory._

  def allServices = {
    if (provisionDB) GestaltMarathonLauncher.LAUNCH_ORDER.flatMap(_.targetService)
    else GestaltMarathonLauncher.LAUNCH_ORDER.flatMap(_.targetService).filterNot(_ == "data")
  }

  def getAppSpec(name: String, globals: JsValue): AppSpec = {
    name match {
      case "data" => getData(globals)
      case "rabbit" => getRabbit(globals)
      case "security" => getSecurity(globals)
      case "meta" => getMeta(globals)
      case "api-proxy" => getApiProxy(globals)
      case "ui" => getUI(globals)
      case "kong" => getKong(globals)
      case "api-gateway" => getApiGateway(globals)
      case "lambda" => getLambda(globals)
      case "policy" => getPolicy(globals)
    }
  }

  private[this] def getData(globals: JsValue): AppSpec = {
    val dbConfig = GlobalDBConfig(globals)
    AppSpec(
      name = "data",
      volumes = Some(Seq(marathon.Volume(
        containerPath = "pgdata",
        mode = "RW",
        persistent = VolumePersistence(
          size = provisionedDBSize
        )
      ))),
      env = Map(
        "POSTGRES_USER" -> dbConfig.username,
        "POSTGRES_PASSWORD" -> dbConfig.password,
        "PGDATA" -> "/mnt/mesos/sandbox/pgdata"
      ),
      image = dockerImages("data"),
      network = ContainerInfo.DockerInfo.Network.BRIDGE,
      ports = Some(Seq(PortSpec(number = 5432, name = "sql", labels = Map("VIP_0" -> dest("data"))))),
      cpus = 0.50,
      mem = 256,
      healthChecks = Seq(HealthCheck(
        portIndex = 0, protocol = "TCP", path = ""
      ))
    )
  }

  private[this] def getSecurity(globals: JsValue): AppSpec = {
    val dbConfig = GlobalDBConfig(globals)
    val secConfig = (globals \ "security")
    val labels = getVhostLabels("security")
    AppSpec(
      name = "security",
      env = Map(
        "DATABASE_HOSTNAME" -> dbConfig.hostname,
        "DATABASE_PORT" -> dbConfig.port.toString,
        "DATABASE_NAME" -> (dbConfig.prefix + "security"),
        "DATABASE_USERNAME" -> dbConfig.username,
        "DATABASE_PASSWORD" -> dbConfig.password,
        "OAUTH_RATE_LIMITING_AMOUNT" -> (secConfig \ "oauth" \ "rateLimitingAmount").asOpt[Int].map(_.toString).getOrElse("100"),
        "OAUTH_RATE_LIMITING_PERIOD" -> (secConfig \ "oauth" \ "rateLimitingPeriod").asOpt[Int].map(_.toString).getOrElse("1")
      ),
      args = Some(Seq("-J-Xmx512m")),
      image = dockerImages("security"),
      network = ContainerInfo.DockerInfo.Network.BRIDGE,
      ports = Some(Seq(PortSpec(number = 9000, name = "http-api", labels = Map("VIP_0" -> dest("security"))))),
      cpus = 0.5,
      mem = 768,
      healthChecks = Seq(HealthCheck(
          portIndex = 0, protocol = "HTTP", path = "/health"
      )),
      readinessCheck = Some(MarathonReadinessCheck(
        path = "/init",
        portName = "http-api",
        httpStatusCodesForReady = Seq(200),
        intervalSeconds = 5,
        timeoutSeconds = 10
      )),
      labels = labels
    )
  }

  private[this] def getMeta(globals: JsValue): AppSpec = {
    val dbConfig = GlobalDBConfig(globals)
    val secConfig = (globals \ "security")
    val labels = getVhostLabels("meta")
    AppSpec(
      name = "meta",
      env = Map(
        "DATABASE_HOSTNAME" -> dbConfig.hostname,
        "DATABASE_PORT" -> dbConfig.port.toString,
        "DATABASE_NAME" -> (dbConfig.prefix + "meta"),
        "DATABASE_USERNAME" -> dbConfig.username,
        "DATABASE_PASSWORD" -> dbConfig.password,
        //
        "GESTALT_APIGATEWAY" -> s"http://${dest("api-gateway")}",
        "GESTALT_LAMBDA" -> s"http://${dest("lambda")}",
        //
        "GESTALT_SECURITY_PROTOCOL" -> "http",
        "GESTALT_SECURITY_HOSTNAME" -> VIP,
        "GESTALT_SECURITY_PORT" -> ports("security"),
        "GESTALT_SECURITY_KEY" -> (secConfig \ "apiKey").asOpt[String].getOrElse("missing"),
        "GESTALT_SECURITY_SECRET" -> (secConfig \ "apiSecret").asOpt[String].getOrElse("missing"),
        "GESTALT_SECURITY_REALM" ->
          TLD.map("https://security." + _ + "/root/oauth/issue")
              .orElse((secConfig \ "realm").asOpt[String])
              .getOrElse(s"http://${dest("security")}"),
        //
        "RABBIT_HOST" -> VIP,
        "RABBIT_PORT" -> ports("rabbit"),
        "RABBIT_HTTP_PORT" -> ports("rabbit-http"),
        "RABBIT_EXCHANGE" -> RABBIT_EXCHANGE,
        "RABBIT_ROUTE" -> "policy"
      ),
      args = Some(Seq("-J-Xmx512m")),
      image = dockerImages("meta"),
      network = ContainerInfo.DockerInfo.Network.BRIDGE,
      ports = Some(Seq(PortSpec(number = 9000, name = "http-api", labels = Map("VIP_0" -> dest("meta"))))),
      cpus = 0.5,
      mem = 768,
      healthChecks = Seq(HealthCheck(portIndex = 0, protocol = "HTTP", path = "/health")),
      readinessCheck = Some(MarathonReadinessCheck(
        path = "/health",
        portName = "http-api",
        httpStatusCodesForReady = Seq(200,401,403),
        intervalSeconds = 5,
        timeoutSeconds = 10
      )),
      labels = labels
    )
  }

  private[this] def getKong(globals: JsValue): AppSpec = {
    val dbConfig = GlobalDBConfig(globals)
    val labels = getVhostLabels("kong")
    AppSpec(
      name = "kong",
      env = Map(
        "POSTGRES_HOST" -> dbConfig.hostname,
        "POSTGRES_PORT" -> dbConfig.port.toString,
        "POSTGRES_DATABASE" -> s"${dbConfig.prefix}kong",
        "POSTGRES_USER" -> dbConfig.username,
        "POSTGRES_PASSWORD" -> dbConfig.password
      ),
      image = dockerImages("kong"),
      network = ContainerInfo.DockerInfo.Network.BRIDGE,
      ports = Some(Seq(
        PortSpec(number = 8000, name = "gateway-api", labels = Map("VIP_0" -> dest("kong-gateway"))),
        PortSpec(number = 8001, name = "service-api", labels = Map("VIP_0" -> dest("kong-service")))
      )),
      cpus = 0.25,
      mem = 128,
      healthChecks = Seq(HealthCheck(portIndex = 1, protocol = "HTTP", path = "/cluster")),
      readinessCheck = None,
      labels = labels
    )
  }

  private[this] def getPolicy(globals: JsValue): AppSpec = {
    val dbConfig = GlobalDBConfig(globals)
    val secConfig = (globals \ "security")
    AppSpec(
      name = "policy",
      args = Some(Seq("-J-Xmx512m")),
      env = Map(
        "LAMBDA_HOST" -> VIP,
        "LAMBDA_PORT" -> ports("lambda"),
        "LAMBDA_USER" -> (secConfig \ "apiKey").asOpt[String].getOrElse("missing"),
        "LAMBDA_PASSWORD" -> (secConfig \ "apiSecret").asOpt[String].getOrElse("missing"),
        //
        "META_PROTOCOL" -> "http",
        "META_HOST" -> VIP,
        "META_PORT" -> ports("meta"),
        "META_USER" -> (secConfig \ "apiKey").asOpt[String].getOrElse("missing"),
        "META_PASSWORD" -> (secConfig \ "apiSecret").asOpt[String].getOrElse("missing"),
        //
        "BINDING_UPDATE_SECONDS" -> "20",
        "CONNECTION_CHECK_TIME_SECONDS" -> "60",
        "POLICY_MAX_WORKERS" -> "20",
        "POLICY_MIN_WORKERS" -> "5",
        //
        "RABBIT_HOST" -> VIP,
        "RABBIT_PORT" -> ports("rabbit"),
        "RABBIT_EXCHANGE" -> RABBIT_EXCHANGE,
        "RABBIT_ROUTE" -> "policy"
      ),
      image = dockerImages("policy"),
      network = ContainerInfo.DockerInfo.Network.BRIDGE,
      ports = Some(Seq(
        PortSpec(number = 9000, name = "http-api", labels = Map("VIP_0" -> dest("policy")))
      )),
      cpus = 0.25,
      mem = 768,
      healthChecks = Seq(HealthCheck(
        path = "/health",
        protocol = "HTTP",
        portIndex = 0
      )),
      readinessCheck = Some(MarathonReadinessCheck(
        protocol = "HTTP",
        path = "/health",
        portName = "http-api",
        intervalSeconds = 5
      ))
    )
  }

  private[this] def getLambda(globals: JsValue): AppSpec = {
    val dbConfig = GlobalDBConfig(globals)
    val secConfig = (globals \ "security")
    val labels = getVhostLabels("lambda")
    AppSpec(
      name = "lambda",
      env = Map(
        "LAMBDA_DATABASE_NAME" -> s"${dbConfig.prefix}lambda",
        "LAMBDA_DATABASE_HOSTNAME" -> s"${dbConfig.hostname}",
        "LAMBDA_DATABASE_PORT" -> s"${dbConfig.port}",
        "LAMBDA_DATABASE_USER" -> s"${dbConfig.username}",
        "LAMBDA_DATABASE_PASSWORD" -> s"${dbConfig.password}",
        "LAMBDA_FLYWAY_MIGRATE" -> "true",
        //
        "GESTALT_SECURITY_PROTOCOL" -> "http",
        "GESTALT_SECURITY_HOSTNAME" -> VIP,
        "GESTALT_SECURITY_PORT" -> ports("security"),
        "GESTALT_SECURITY_KEY" -> (secConfig \ "apiKey").asOpt[String].getOrElse("missing"),
        "GESTALT_SECURITY_SECRET" -> (secConfig \ "apiSecret").asOpt[String].getOrElse("missing"),
        //
        "META_PROTOCOL" -> "http",
        "META_HOSTNAME" -> VIP,
        "META_PORT" -> ports("meta"),
        "META_USER" -> (secConfig \ "apiKey").asOpt[String].getOrElse("missing"),
        "META_PASSWORD" -> (secConfig \ "apiSecret").asOpt[String].getOrElse("missing"),
        //
        "MESOS_ROLE" -> "*",
        "MESOS_NATIVE_JAVA_LIBRARY" -> "/usr/lib/libmesos.so",
        "MESOS_NATIVE_LIBRARY" -> "/usr/lib/libmesos.so",
        "MESOS_MASTER_CONNECTION" -> "zk://master.mesos:2181/mesos",
        //
        "NEW_RELIC_LICENSE_KEY" -> "64300aae4a006efc6fa13ab9f88386f186707003",
        "CACHE_EXPIRE_SECONDS" -> "900",
        "SCHEDULER_NAME" -> "gestalt-lambda-scheduler",
        "MAX_LAMBDAS_PER_OFFER" -> "6",
        "OFFER_TTL" -> "5"
      ),
      image = dockerImages("lambda"),
      network = ContainerInfo.DockerInfo.Network.HOST,
      ports = Some(Seq(
        PortSpec(number = 9000, name = "http-api", labels = Map("VIP_0" -> dest("lambda")))
      )),
      cpus = 0.5,
      cmd = Some("./bin/gestalt-lambda -Dhttp.port=$PORT0 -Dlogger.file=/opt/docker/conf/logback.xml -J-Xmx1024m"),
      mem = 1536,
      healthChecks = Seq(HealthCheck(
        path = "/health",
        protocol = "HTTP",
        portIndex = 0
      )),
      readinessCheck = Some(MarathonReadinessCheck(
        protocol = "HTTP",
        path = "/health",
        portName = "http-api",
        intervalSeconds = 5
      )),
      labels = labels
    )
  }

  private[this] def getApiGateway(globals: JsValue): AppSpec = {
    val dbConfig = GlobalDBConfig(globals)
    val secConfig = (globals \ "security")
    val labels = Map.empty[String,String]
    AppSpec(
      name = "api-gateway",
      args = Some(Seq("-J-Xmx512m")),
      env = Map(
        "GATEWAY_DATABASE_HOSTNAME" -> s"${dbConfig.hostname}",
        "GATEWAY_DATABASE_MIGRATE" -> "true",
        "GATEWAY_DATABASE_NAME" -> s"${dbConfig.prefix}apigateway",
        "GATEWAY_DATABASE_PASSWORD" -> s"${dbConfig.password}",
        "GATEWAY_DATABASE_PORT" -> s"${dbConfig.port}",
        "GATEWAY_DATABASE_USER" -> s"${dbConfig.username}",
        "GESTALT_SECURITY_PROTOCOL" -> "http",
        "GESTALT_SECURITY_HOSTNAME" -> VIP,
        "GESTALT_SECURITY_PORT" -> ports("security"),
        "GESTALT_SECURITY_KEY" -> (secConfig \ "apiKey").asOpt[String].getOrElse("missing"),
        "GESTALT_SECURITY_SECRET" -> (secConfig \ "apiSecret").asOpt[String].getOrElse("missing"),
        "OVERRIDE_UPSTREAM_PROTOCOL" -> "http"
      ),
      image = dockerImages("api-gateway"),
      network = ContainerInfo.DockerInfo.Network.BRIDGE,
      ports = Some(Seq(
        PortSpec(number = 9000, name = "http-api", labels = Map("VIP_0" -> dest("api-gateway")))
      )),
      cpus = 0.25,
      mem = 768,
      healthChecks = Seq(HealthCheck(
        path = "/health",
        protocol = "HTTP",
        portIndex = 0
      )),
      readinessCheck = Some(MarathonReadinessCheck(
        protocol = "HTTP",
        path = "/health",
        portName = "http-api",
        intervalSeconds = 5
      )),
      labels = labels
    )
  }

  private[this] def getApiProxy(globals: JsValue): AppSpec = {
    AppSpec(
      name = "api-proxy",
      env = Map(
        "API_URL" -> s"http://${dest("meta")}",
        "SEC_URL" -> s"http://${dest("security")}"
      ),
      image = dockerImages("api-proxy"),
      network = ContainerInfo.DockerInfo.Network.BRIDGE,
      ports = Some(Seq(
        PortSpec(number = 8888, name = "http", labels = Map("VIP_0" -> dest("api-proxy")))
      )),
      cpus = 0.25,
      mem = 128,
      healthChecks = Seq(HealthCheck(
        path = "/service-status",
        protocol = "HTTP",
        portIndex = 0
      )),
      readinessCheck = Some(MarathonReadinessCheck(
        protocol = "HTTP",
        path = "/service-status",
        portName = "http",
        intervalSeconds = 5
      ))
    )
  }

  private[this] def getUI(globals: JsValue): AppSpec = {
    val labels = getVhostLabels("ui")
    AppSpec(
      name = "ui",
      env = Map(
        "API_URL" -> s"http://${dest("api-proxy")}"
      ),
      image = dockerImages("ui"),
      network = ContainerInfo.DockerInfo.Network.BRIDGE,
      ports = Some(Seq(
        PortSpec(number = 80, name = "http", labels = Map("VIP_0" -> dest("ui")))
      )),
      cpus = 0.25,
      mem = 128,
      healthChecks = Seq(HealthCheck(
        path = "/#/login",
        protocol = "HTTP",
        portIndex = 0
      )),
      readinessCheck = Some(MarathonReadinessCheck(
        protocol = "HTTP",
        path = "/#/login",
        portName = "http",
        intervalSeconds = 5
      )),
      labels = labels
    )
  }

  private[this] def getRabbit(globals: JsValue): AppSpec = {
    AppSpec(
      name = "rabbit",
      env = Map.empty,
      image = dockerImages("rabbit"),
      network = ContainerInfo.DockerInfo.Network.BRIDGE,
      ports = Some(Seq(
        PortSpec(number = 5672,  name = "service-api", labels = Map("VIP_0" -> dest("rabbit"))),
        PortSpec(number = 15672, name = "http-api",    labels = Map("VIP_0" -> dest("rabbit-http")))
      )),
      cpus = 0.50,
      mem = 256,
      healthChecks = Seq(HealthCheck(
        portIndex = 1, protocol = "HTTP", path = "/"
      )),
      readinessCheck = Some(MarathonReadinessCheck(
        path = "/",
        portName = "http-api",
        httpStatusCodesForReady = Seq(200),
        intervalSeconds = 5,
        timeoutSeconds = 10
      )),
      labels = Map.empty
    )
  }

  implicit private[this] def getVariables(env: Map[String,String]): Environment = {
    val builder = Environment.newBuilder()
    env.foreach {
      case (name,value) => builder.addVariables(Variable.newBuilder
        .setName(name)
        .setValue(value)
      )
    }
    builder.build
  }

  def getMarathonPayload(name: String, globals: JsValue): MarathonAppPayload = toMarathonPayload(getAppSpec(name, globals), globals)

  def toMarathonPayload(app: AppSpec, globals: JsValue): MarathonAppPayload = {
    val cleanPrefix = "/" + appGroup.stripPrefix("/").stripSuffix("/") + "/"
    val isBridged = app.network.getValueDescriptor.getName == "BRIDGE"
    MarathonAppPayload(
      id = cleanPrefix + app.name,
      args = app.args,
      env = app.env,
      instances = app.numInstances,
      cpus = app.cpus,
      cmd = app.cmd,
      mem = app.mem,
      disk = 0,
      requirePorts = true,
      container = MarathonContainerInfo(
        `type` = "DOCKER",
        volumes = app.volumes,
        docker = Some(MarathonDockerContainer(
          image = app.image,
          network = app.network.getValueDescriptor.getName,
          privileged = false,
          parameters = Seq(),
          forcePullImage = true,
          portMappings = if (isBridged) app.ports.map {_.map(
            p => DockerPortMapping(containerPort = p.number, name = Some(p.name), protocol = "tcp", labels = Some(p.labels))
          ) } else None
        ))
      ),
      labels = app.labels,
      healthChecks = app.healthChecks.map( hc => MarathonHealthCheck(
        path = hc.path,
        protocol = hc.protocol,
        portIndex = hc.portIndex,
        gracePeriodSeconds = 300,
        intervalSeconds = 30,
        timeoutSeconds = 15,
        maxConsecutiveFailures = 4
      ) ),
      readinessCheck = app.readinessCheck,
      portDefinitions = if (!isBridged) app.ports.map {_.map(
        p => PortDefinition(port = p.number, protocol = "tcp", name = Some(p.name), labels = Some(p.labels))
      )} else None
    )
  }

  def toTaskInfo(app: AppSpec, offer: Offer): TaskInfo = {
    val commandInfo = CommandInfo.newBuilder()
      .setShell(false)
      .setEnvironment(app.env)

    val containerInfo = Protos.ContainerInfo.newBuilder
      .setType( Protos.ContainerInfo.Type.DOCKER )
      .setDocker( ContainerInfo.DockerInfo.newBuilder
        .setImage(app.image)
        .setForcePullImage(true)
        .setNetwork(app.network)
        .build
      )

    TaskInfo.newBuilder()
      .setName( app.name )
      .addResources(
        Resource.newBuilder()
          .setName("cpus")
          .setType(Value.Type.SCALAR)
          .setScalar(Value.Scalar.newBuilder().setValue( app.cpus ))
      )
      .addResources(
        Resource.newBuilder()
          .setName("mem")
          .setType(Value.Type.SCALAR)
          .setScalar(Value.Scalar.newBuilder().setValue( app.mem ))
      )
      .setCommand(commandInfo)
      .setContainer(containerInfo)
      .setSlaveId(offer.getSlaveId)
      .setTaskId(
        Protos.TaskID.newBuilder().setValue(app.name + "-" + UUID.randomUUID.toString)
      )
      .build()
  }

}

case object GestaltTaskFactory {
  val DEFAULT_APP_GROUP = "gestalt-framework-tasks"
}

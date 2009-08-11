/**
 * Copyright (C) 2009 Scalable Solutions.
 */

package se.scalablesolutions.akka.kernel

import com.sun.grizzly.http.SelectorThread
import com.sun.grizzly.http.servlet.ServletAdapter
import com.sun.grizzly.standalone.StaticStreamAlgorithm

import javax.ws.rs.core.UriBuilder
import java.io.File
import java.net.URLClassLoader

import net.lag.configgy.{Config, Configgy, RuntimeEnvironment, ParseException}

import kernel.jersey.AkkaCometServlet
import kernel.nio.RemoteServer
import kernel.state.CassandraStorage
import kernel.util.Logging

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
object Kernel extends Logging {
  @volatile private var hasBooted = false

  val HOME = {
    val home = System.getenv("AKKA_HOME")
    if (home == null || home == "") None 
    else Some(home)
  }

  val config = setupConfig

  val BOOT_CLASSES = config.getList("akka.boot")

  val RUN_REMOTE_SERVICE = config.getBool("akka.remote.service", true)
  val STORAGE_SYSTEM = config.getString("akka.storage.system", "cassandra")

  val RUN_REST_SERVICE = config.getBool("akka.rest.service", true)
  val REST_HOSTNAME = kernel.Kernel.config.getString("akka.rest.hostname", "localhost")
  val REST_URL = "http://" + REST_HOSTNAME
  val REST_PORT = kernel.Kernel.config.getInt("akka.rest.port", 9998)

  // FIXME add API to shut server down gracefully
  private var remoteServer: RemoteServer = _
  private var jerseySelectorThread: SelectorThread = _
  private val startTime = System.currentTimeMillis

  def main(args: Array[String]) = boot

  def boot = synchronized {
    if (!hasBooted) {
      printBanner
      log.info("Starting Akka...")

      if (RUN_REMOTE_SERVICE) startRemoteService

      STORAGE_SYSTEM match {
        case "cassandra" =>     startCassandra
        case "terracotta" =>    throw new UnsupportedOperationException("terracotta storage backend is not yet supported")
        case "redis" =>         throw new UnsupportedOperationException("redis storage backend is not yet supported")
        case "voldemort" =>     throw new UnsupportedOperationException("voldemort storage backend is not yet supported")
        case "tokyo-cabinet" => throw new UnsupportedOperationException("tokyo-cabinet storage backend is not yet supported")
        case _ =>               throw new UnsupportedOperationException("Unknown storage system [" + STORAGE_SYSTEM + "]")
      }

      if (RUN_REST_SERVICE) startJersey

      runApplicationBootClasses

      log.info("Akka started successfully")
      hasBooted = true
    }
  }
  
  def uptime = (System.currentTimeMillis - startTime) / 1000

  def setupConfig: Config = {
    try {
      Configgy.configureFromResource("akka.conf", getClass.getClassLoader)
      log.info("Config loaded from the application classpath.")
    } catch {
      case e: ParseException =>
        try {
          if (HOME.isDefined) {
            val configFile = HOME.get + "/config/akka.conf"
            log.info("AKKA_HOME is defined to [%s], loading config from [%s].", HOME.get, configFile)
            Configgy.configure(configFile)
          } else throw new IllegalStateException("AKKA_HOME is not defined and no 'akka.conf' can be found on the classpath, aborting")
        } catch {
          case e: ParseException => throw new IllegalStateException("AKKA_HOME is not defined and no 'akka.conf' can be found on the classpath, aborting")
        }
    }
    //val runtime = new RuntimeEnvironment(getClass)
    //runtime.load(args)
    val config = Configgy.config
    config.registerWithJmx("com.scalablesolutions.akka.config")
    // FIXME fix Configgy JMX subscription to allow management
    // config.subscribe { c => configure(c.getOrElse(new Config)) }
    config
  }

  private[akka] def runApplicationBootClasses: Unit = {
    val loader =
      if (getClass.getClassLoader.getResourceAsStream("akka.conf") != null) getClass.getClassLoader
      else if (HOME.isDefined) {
        val CONFIG = HOME.get + "/config"
        val DEPLOY = HOME.get + "/deploy"
        val DEPLOY_DIR = new File(DEPLOY)
        if (!DEPLOY_DIR.exists) { log.error("Could not find a deploy directory at [" + DEPLOY + "]"); System.exit(-1) }
        val toDeploy = for (f <- DEPLOY_DIR.listFiles().toArray.toList.asInstanceOf[List[File]]) yield f.toURL
        log.info("Deploying applications from [%s]: [%s]", DEPLOY, toDeploy.toArray.toList)
        new URLClassLoader(toDeploy.toArray, getClass.getClassLoader)
      } else throw new IllegalStateException("AKKA_HOME is not defined and no 'akka.conf' can be found on the classpath, aborting")
    for (clazz <- BOOT_CLASSES) {
      log.info("Loading boot class [%s]", clazz)
      loader.loadClass(clazz).newInstance
    }
  }
  
  private[akka] def startRemoteService = {
    // FIXME manage remote serve thread for graceful shutdown
    val remoteServerThread = new Thread(new Runnable() { def run = RemoteServer.start }, "akka remote service")
    remoteServerThread.start
  }

  private[akka] def startCassandra = if (config.getBool("akka.storage.cassandra.service", true)) {
    System.setProperty("cassandra", "")
    if (HOME.isDefined) System.setProperty("storage-config", HOME.get + "/config/")
    else if (System.getProperty("storage-config", "NIL") == "NIL") throw new IllegalStateException("AKKA_HOME and -Dstorage-config=... is not set. Can't start up Cassandra. Either set AKKA_HOME or set the -Dstorage-config=... variable to the directory with the Cassandra storage-conf.xml file.")
    CassandraStorage.start
  }

  private[akka] def startJersey = {
    val uri = UriBuilder.fromUri(REST_URL).port(REST_PORT).build()

    val scheme = uri.getScheme
    if (!scheme.equalsIgnoreCase("http")) throw new IllegalArgumentException("The URI scheme, of the URI " + REST_URL + ", must be equal (ignoring case) to 'http'")

    val adapter = new ServletAdapter
    adapter.setHandleStaticResources(true)
    adapter.setServletInstance(new AkkaCometServlet)
    adapter.setContextPath(uri.getPath)
    if (HOME.isDefined) adapter.setRootFolder(HOME.get + "/deploy/root")
    log.info("REST service root path: [" + adapter.getRootFolder + "] and context path [" + adapter.getContextPath + "] ")

    val ah = new com.sun.grizzly.arp.DefaultAsyncHandler
    ah.addAsyncFilter(new com.sun.grizzly.comet.CometAsyncFilter)
    jerseySelectorThread = new SelectorThread
    jerseySelectorThread.setAlgorithmClassName(classOf[StaticStreamAlgorithm].getName)
    jerseySelectorThread.setPort(REST_PORT)
    jerseySelectorThread.setAdapter(adapter)
    jerseySelectorThread.setEnableAsyncExecution(true)
    jerseySelectorThread.setAsyncHandler(ah)
    jerseySelectorThread.listen

    log.info("REST service started successfully. Listening to port [" + REST_PORT + "]")
  }

  private def printBanner = {
    log.info(
"""==============================
        __    __
 _____  |  | _|  | _______
 \__  \ |  |/ /  |/ /\__  \
  / __ \|    <|    <  / __ \_
 (____  /__|_ \__|_ \(____  /
      \/     \/    \/     \/
""")
    log.info("     Running version " + config.getString("akka.version", "Awesome"))
    log.info("==============================")
  }
  
  private def cassandraBenchmark = {
    val NR_ENTRIES = 100000

    println("=================================================")
    var start = System.currentTimeMillis
    for (i <- 1 to NR_ENTRIES) CassandraStorage.insertMapStorageEntryFor("test", i.toString, "data")
    var end = System.currentTimeMillis
    println("Writes per second: " + NR_ENTRIES / ((end - start).toDouble / 1000))

    println("=================================================")
    start = System.currentTimeMillis
    val entries = new scala.collection.mutable.ArrayBuffer[Tuple2[String, String]]
    for (i <- 1 to NR_ENTRIES) entries += (i.toString, "data")
    CassandraStorage.insertMapStorageEntriesFor("test", entries.toList)
    end = System.currentTimeMillis
    println("Writes per second - batch: " + NR_ENTRIES / ((end - start).toDouble / 1000))
    
    println("=================================================")
    start = System.currentTimeMillis
    for (i <- 1 to NR_ENTRIES) CassandraStorage.getMapStorageEntryFor("test", i.toString)
    end = System.currentTimeMillis
    println("Reads per second: " + NR_ENTRIES / ((end - start).toDouble / 1000))

    System.exit(0)
  }
}



  
/*
  //import voldemort.client.{SocketStoreClientFactory, StoreClient, StoreClientFactory}
  //import voldemort.server.{VoldemortConfig, VoldemortServer}
  //import voldemort.versioning.Versioned

    private[this] var storageFactory: StoreClientFactory = _
    private[this] var storageServer: VoldemortServer = _
  */

//  private[akka] def startVoldemort = {
//  val VOLDEMORT_SERVER_URL = "tcp://" + SERVER_URL
//  val VOLDEMORT_SERVER_PORT = 6666
//  val VOLDEMORT_BOOTSTRAP_URL = VOLDEMORT_SERVER_URL + ":" + VOLDEMORT_SERVER_PORT
//    // Start Voldemort server
//    val config = VoldemortConfig.loadFromVoldemortHome(Boot.HOME)
//    storageServer = new VoldemortServer(config)
//    storageServer.start
//    log.info("Replicated persistent storage server started at %s", VOLDEMORT_BOOTSTRAP_URL)
//
//    // Create Voldemort client factory
//    val numThreads = 10
//    val maxQueuedRequests = 10
//    val maxConnectionsPerNode = 10
//    val maxTotalConnections = 100
//    storageFactory = new SocketStoreClientFactory(
//      numThreads,
//      numThreads,
//      maxQueuedRequests,
//      maxConnectionsPerNode,
//      maxTotalConnections,
//      VOLDEMORT_BOOTSTRAP_URL)
//
//    val name = this.getClass.getName
//    val storage = getStorageFor("actors")
////    val value = storage.get(name)
//    val value = new Versioned("state")
//    //value.setObject("state")
//    storage.put(name, value)
//  }
//
//  private[akka] def getStorageFor(storageName: String): StoreClient[String, String] =
//    storageFactory.getStoreClient(storageName)

   // private[akka] def startZooKeeper = {
  //import org.apache.zookeeper.jmx.ManagedUtil
  //import org.apache.zookeeper.server.persistence.FileTxnSnapLog
  //import org.apache.zookeeper.server.ServerConfig
  //import org.apache.zookeeper.server.NIOServerCnxn
  //  val ZOO_KEEPER_SERVER_URL = SERVER_URL
  //  val ZOO_KEEPER_SERVER_PORT = 9898
  //   try {
  //     ManagedUtil.registerLog4jMBeans
  //     ServerConfig.parse(args)
  //   } catch {
  //     case e: JMException => log.warning("Unable to register log4j JMX control: s%", e)
  //     case e => log.fatal("Error in ZooKeeper config: s%", e)
  //   }
  //   val factory = new ZooKeeperServer.Factory() {
  //     override def createConnectionFactory = new NIOServerCnxn.Factory(ServerConfig.getClientPort)
  //     override def createServer = {
  //       val server = new ZooKeeperServer
  //       val txLog = new FileTxnSnapLog(
  //         new File(ServerConfig.getDataLogDir),
  //         new File(ServerConfig.getDataDir))
  //       server.setTxnLogFactory(txLog)
  //       server
  //     }
  //   }
  //   try {
  //     val zooKeeper = factory.createServer
  //     zooKeeper.startup
  //     log.info("ZooKeeper started")
  //     // TODO: handle clean shutdown as below in separate thread
  //     // val cnxnFactory = serverFactory.createConnectionFactory
  //     // cnxnFactory.setZooKeeperServer(zooKeeper)
  //     // cnxnFactory.join
  //     // if (zooKeeper.isRunning) zooKeeper.shutdown
  //   } catch { case e => log.fatal("Unexpected exception: s%",e) }
  // }

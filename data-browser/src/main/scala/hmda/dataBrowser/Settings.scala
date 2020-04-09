package hmda.dataBrowser

import java.math.BigInteger
import java.security.MessageDigest

import com.typesafe.config.{Config, ConfigFactory}

import scala.concurrent.duration._

trait Settings {
  private val config: Config = ConfigFactory.load()

  private def getDuration(key: String): FiniteDuration = {
    val duration = config.getDuration(key)
    FiniteDuration(duration.toMillis, MILLISECONDS)
  }

  def md5HashString(s: String): String = {
    val md           = MessageDigest.getInstance("MD5")
    val digest       = md.digest(s.getBytes)
    val bigInt       = new BigInteger(1, digest)
    val hashedString = bigInt.toString(16)
    hashedString
  }
  object server {
    val host: String = config.getString("server.bindings.address")
    val port: Int = config.getInt("server.bindings.port")
    val askTimeout: FiniteDuration = getDuration("server.ask-timeout")
  }

  object database {
    val tableName2018: String = config.getString("dbconfig.table.2018")
    val tableName2017: String = config.getString("dbconfig.table.2017")
  }

  object redis {
    private val host: String = config.getString("redis.hostname")
    private val port: Int = config.getInt("redis.port")
    val url = s"redis://$host:$port"
    val ttl: FiniteDuration = getDuration("redis.ttl")
  }

  object s3 {
    val environment: String = config.getString("server.s3.environment")
    val bucket: String = config.getString("server.s3.public-bucket")
    val url: String = config.getString("server.s3.url")
    val filteredQueries: String =
      config.getString("server.s3.routes.filtered-queries")
  }
}

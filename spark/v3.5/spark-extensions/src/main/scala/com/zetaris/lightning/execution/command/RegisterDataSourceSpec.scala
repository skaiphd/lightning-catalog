
/*
 *
 *  * Copyright 2023 ZETARIS Pty Ltd
 *  *
 *  * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and
 *  * associated documentation files (the "Software"), to deal in the Software without restriction,
 *  * including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense,
 *  * and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so,
 *  * subject to the following conditions:
 *  *
 *  * The above copyright notice and this permission notice shall be included in all copies
 *  * or substantial portions of the Software.
 *  *
 *  * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED,
 *  * INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 *  * NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM,
 *  * DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *  * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 *
 */

package com.zetaris.lightning.execution.command

import com.zetaris.lightning.execution.command.DataSourceType._
import com.zetaris.lightning.model.LightningModelFactory
import com.zetaris.lightning.model.serde.DataSource
import org.apache.spark.sql.Row
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.catalyst.expressions.AttributeReference
import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.sql.types.StringType
import org.apache.spark.sql.util.CaseInsensitiveStringMap

import java.util.regex.Pattern

case class RegisterDataSourceSpec(namespace: Array[String],
                                  name: String,
                                  dataSourceType: DataSourceType.DataSourceType,
                                  opts: Map[String, String],
                                  replace: Boolean) extends LightningCommandBase {
  dataSourceType match {
    case JDBC => validateJDBCParams()
    case ICEBERG => validateIcebergParams()
    case DELTA => validateDeltaParams()
    case PARQUET | ORC | AVRO | XML | CSV | JSON => validateFileParams()
    case _ =>
  }

  override val output: Seq[AttributeReference] = Seq(
    AttributeReference("registered", StringType, false)()
  )

  private def catalogOptions(conf: SQLConf) = {
    val prefix = Pattern.compile("^spark\\.sql\\.catalog\\." + name + "\\.(.+)")
    val options = new java.util.HashMap[String, String]
    conf.getAllConfs.foreach {
      case (key, value) =>
        val matcher = prefix.matcher(key)
        if (matcher.matches && matcher.groupCount > 0) options.put(matcher.group(1), value)
    }
    new CaseInsensitiveStringMap(options)
  }

  private def validateFileParams(): Unit = {
    opts.getOrElse("path", {
      opts.getOrElse("paths",
        throw new IllegalArgumentException(s"path option is not provided"))
    })
  }

  private def validateDeltaParams(): Unit = {
    opts.getOrElse(s"path", throw new IllegalArgumentException(s"path option is not provided"))
  }

  private def validateIcebergParams(): Unit = {
    val warehousetype = opts.getOrElse("type",
      throw new IllegalArgumentException(s"iceberg type(type) is not provided"))

    if (warehousetype.toLowerCase == "hadoop") {
      opts.getOrElse(s"warehouse",
        throw new IllegalArgumentException(s"warehouse path(warehouse) is not provided"))
    }
  }

  private def validateJDBCParams(): Unit = {
    opts.getOrElse(s"url",
      throw new IllegalArgumentException(s"jdbc url : url is not provided"))
  }

  override def runCommand(sparkSession: SparkSession): Seq[Row] = {
    val model = LightningModelFactory(dataSourceConfigMap(s"${LightningModelFactory.LIGHTNING_CATALOG}.",
      sparkSession))
    val withoutCatalog = namespace.drop(1)
    val parentNamespace = withoutCatalog.dropRight(1)
    val lastNamespace = namespace.last

    if (!model.listNamespaces(parentNamespace).exists(_.equalsIgnoreCase(lastNamespace))) {
      throw new RuntimeException(s"parent namespace: ${namespace.mkString(".")} is not existing")
    }

    val dataSource = DataSource.DataSource(dataSourceType, withoutCatalog, name, DataSource.toProperties(opts))

    val filePath = model.saveDataSource(dataSource, replace)
    Row(filePath) :: Nil
  }
}


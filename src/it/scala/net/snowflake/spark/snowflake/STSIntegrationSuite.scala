/*
 * Copyright 2015-2016 Snowflake Computing
 * Copyright 2015 Databricks
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

package net.snowflake.spark.snowflake

import com.amazonaws.auth.BasicAWSCredentials
import com.amazonaws.services.securitytoken.AWSSecurityTokenServiceClient
import com.amazonaws.services.securitytoken.model.AssumeRoleRequest

import org.apache.spark.sql.{Row, SaveMode}
import org.apache.spark.sql.types.{IntegerType, StructField, StructType}

import net.snowflake.spark.snowflake.Utils.SNOWFLAKE_SOURCE_NAME

/**
 * Integration tests for accessing S3 using Amazon Security Token Service (STS) credentials.
 */
class STSIntegrationSuite extends IntegrationSuiteBase {

  private var STS_ROLE_ARN: String = _
  private var STS_ACCESS_KEY_ID: String = _
  private var STS_SECRET_ACCESS_KEY: String = _
  private var STS_SESSION_TOKEN: String = _

  override def beforeAll(): Unit = {
    super.beforeAll()
  }

  // TODO: Re-enable once we have STS_ROLE_ARN set up for testing
  ignore("roundtrip save and load") {
    STS_ROLE_ARN = getConfigValue("STS_ROLE_ARN")
    val awsCredentials = new BasicAWSCredentials(AWS_ACCESS_KEY_ID, AWS_SECRET_ACCESS_KEY)
    val stsClient = new AWSSecurityTokenServiceClient(awsCredentials)
    val assumeRoleRequest = new AssumeRoleRequest()
    assumeRoleRequest.setDurationSeconds(900) // this is the minimum supported duration
    assumeRoleRequest.setRoleArn(STS_ROLE_ARN)
    assumeRoleRequest.setRoleSessionName(s"spark-$randomSuffix")
    val creds = stsClient.assumeRole(assumeRoleRequest).getCredentials
    STS_ACCESS_KEY_ID = creds.getAccessKeyId
    STS_SECRET_ACCESS_KEY = creds.getSecretAccessKey
    STS_SESSION_TOKEN = creds.getSessionToken

    val tableName = s"roundtrip_save_and_load$randomSuffix"
    val df = sqlContext.createDataFrame(sc.parallelize(Seq(Row(1))),
      StructType(StructField("a", IntegerType) :: Nil))
    try {
      df.write
        .format(SNOWFLAKE_SOURCE_NAME)
        .options(connectorOptions)
        .option("dbtable", tableName)
        .option("temporary_aws_access_key_id", STS_ACCESS_KEY_ID)
        .option("temporary_aws_secret_access_key", STS_SECRET_ACCESS_KEY)
        .option("temporary_aws_session_token", STS_SESSION_TOKEN)
        .mode(SaveMode.ErrorIfExists)
        .save()

      assert(DefaultJDBCWrapper.tableExists(conn, tableName))
      val loadedDf = sqlContext.read
        .format(SNOWFLAKE_SOURCE_NAME)
        .options(connectorOptions)
        .option("dbtable", tableName)
        .option("temporary_aws_access_key_id", STS_ACCESS_KEY_ID)
        .option("temporary_aws_secret_access_key", STS_SECRET_ACCESS_KEY)
        .option("temporary_aws_session_token", STS_SESSION_TOKEN)
        .load()
      assert(loadedDf.schema.length === 1)
      assert(loadedDf.columns === Seq("a"))
      checkAnswer(loadedDf, Seq(Row(1)))
    } finally {
      conn.prepareStatement(s"drop table if exists $tableName").executeUpdate()
      conn.commit()
    }
  }
}

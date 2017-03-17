/*
 *  Copyright 2015-2017 TWO SIGMA OPEN SOURCE, LLC
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package com.twosigma.flint.timeseries

import com.twosigma.flint.rdd.{ CloseOpen, ParallelCollectionRDD }
import org.apache.spark.{ Partition, SparkContext, TaskContext }
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.types.{ IntegerType, LongType, StructField, StructType }
import org.apache.spark.sql._
import org.apache.spark.sql.functions.{ col, round }

import scala.concurrent.duration.NANOSECONDS
import scala.util.Random

private[flint] trait TimeSeriesTestData {
  protected def sqlContext: SQLContext

  // Helper object to import SQL implicits without a concrete SQLContext
  private object internalImplicits extends SQLImplicits {
    protected override def _sqlContext: SQLContext = sqlContext
  }

  import internalImplicits._
  import TimeSeriesTestData._

  private def changeTimeNotNull(df: DataFrame): DataFrame = {
    val schema = StructType(df.schema.map {
      case StructField("time", LongType, false, meta) => StructField("time", LongType, true, meta)
      case t => t
    })

    sqlContext.createDataFrame(df.rdd, schema)
  }

  protected lazy val testData: TimeSeriesRDD = {
    val df = sqlContext.sparkContext.parallelize(
      TestData(1000) ::
        TestData(1000) ::
        TestData(1000) ::
        TestData(2000) ::
        TestData(2000) ::
        TestData(3000) ::
        TestData(3000) ::
        TestData(3000) ::
        TestData(3000) ::
        TestData(3000) ::
        TestData(4000) ::
        TestData(5000) ::
        TestData(5000) :: Nil
    ).toDF()
    TimeSeriesRDD.fromDF(changeTimeNotNull(df))(isSorted = true, timeUnit = NANOSECONDS)
  }

  protected lazy val cycleData1: (TimeSeriesRDD, CycleMetaData) = generateCycleData(0)
  protected lazy val cycleData2: (TimeSeriesRDD, CycleMetaData) = generateCycleData(1)

  /**
   * Meta data for the generated cycle data. Metadata is used by test to decide arguments for various functions.
   * See below for how the data looks like.
   */
  case class CycleMetaData(cycleWidth: Long, intervalWidth: Long)

  /**
   * Generate cycle data. Generated data has multiple intervals, each interval has the multiple cycles.
   *
   * For instance, for cycleWidth = 1000, intervalWidth = 10000, beginCycleOffset = 3, endCycleOffset = 8:
   *
   * Interval 1:
   * [3000, 4000, 5000, 6000, 7000]
   * Interval 2:
   * [13000, 14000, 15000, 16000, 17000]
   * ...
   *
   */
  private def generateCycleData(salt: Long): (TimeSeriesRDD, CycleMetaData) = {
    val begin = 0L
    val numIntervals = 13
    val cyclesPerInterval = 10
    val beginCycleOffset = 3
    val endCycleOffset = 8
    val cycleWidth = 1000000L // 1 millis
    val intervalWitdh = cycleWidth * cyclesPerInterval
    val seed = 123456789L

    var df = new TimeSeriesGenerator(
      sqlContext.sparkContext,
      begin = begin,
      end = numIntervals * cycleWidth, frequency = cycleWidth
    )(
      columns = Seq(
        "v1" -> { (_: Long, _: Int, r: Random) => r.nextDouble() }
      ),
      ids = 1 to 10,
      ratioOfCycleSize = 0.8,
      seed = seed + salt
    ).generate().toDF

    df = df.withColumn("index", (df("time") / intervalWitdh).cast(IntegerType))
      .withColumn("cycleOffset", (df("time") % intervalWitdh / cycleWidth).cast(IntegerType))
      .where(col("cycleOffset") > beginCycleOffset)
      .where(col("cycleOffset") < endCycleOffset)
      .drop("cycleOffset")

    val rows = df.queryExecution.executedPlan.executeCollect().toSeq
    val indexColumn = df.schema.fieldIndex("index")
    val groupedRows = rows.groupBy(_.getInt(indexColumn)).toSeq.sortBy(_._1).map(_._2)

    val rdd = new ParallelCollectionRDD[InternalRow](sqlContext.sparkContext, groupedRows)
    val ranges = df.select("index").distinct().collect().map(_.getInt(0)).sorted.map{
      case index =>
        CloseOpen(index * intervalWitdh, Some((index + 1) * intervalWitdh))
    }
    val tsrdd = TimeSeriesRDD.fromDFWithRanges(DFConverter.toDataFrame(sqlContext, df.schema, rdd), ranges)
    (tsrdd, CycleMetaData(cycleWidth, intervalWitdh))
  }
}

private[flint] object TimeSeriesTestData {
  case class TestData(time: Long)
}

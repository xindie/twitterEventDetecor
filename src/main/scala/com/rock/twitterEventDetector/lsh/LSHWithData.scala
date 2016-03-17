package com.rock.twitterEventDetector.lsh

/**
  * Created by maruf on 09/08/15.
  */

import com.rock.twitterEventDetector.lsh.partitioners.BucketPartitioner
import org.apache.spark.broadcast.Broadcast
import org.apache.spark.{SparkContext, SparkConf}
import org.apache.spark.mllib.linalg.SparseVector
import org.apache.spark.rdd.RDD
import org.apache.spark.storage.StorageLevel

import scala.collection.immutable.IndexedSeq

/** Build LSH model with data RDD. Hash each vector number of hashTable times and stores in a bucket.
  *
  * @param data RDD of sparse vectors with vector Ids. RDD(vec_id, SparseVector)
  * @param m max number of possible elements in a vector
  * @param numHashFunc number of hash functions
  * @param numHashTables number of hashTables.
  *
  * */
class LSHWithData(data : RDD[(Long, SparseVector)] = null, m: Int = 0, numHashFunc : Int = 4, numHashTables : Int = 4) extends Serializable {

//  /**
//    *
//    * @param data
//    * @param hashFunctions
//    * @return
//    */
//  private def hashVector(data: SparseVector, hashFunctions: Broadcast[IndexedSeq[(Hasher, Int)]]): List[(Int, String)] = {
//    hashFunctions.value.map(a => (a._2 % numHashTables, a._1.hash(data)))
//      .groupBy(_._1)
//      .map(x => (x._1, x._2.map(_._2).mkString(""))).toList
//  }

  /**
    *
    * @param data
    * @param hashFunctions
    * @return
    */
  private def hashVector(data: SparseVector, hashFunctions: Broadcast[IndexedSeq[(Int, Hasher)]]): List[(Int, String)] = {
    hashFunctions.value.map(a => (a._1 % numHashTables, a._2.hash(data)))
      .groupBy(_._1)
      .map(x => (x._1, x._2.map(_._2).mkString(""))).toList
  }

  /**
    *
    * @param sc
    * @return
    */
  def run(sc: SparkContext) : LSHModelWithData = {


   // val hashFunctions: IndexedSeq[(Hasher, Int)] = (0 until numHashFunc * numHashTables).map(i => (Hasher(m),i))
    val hashFunctions: IndexedSeq[(Int, Hasher)] = (0 until numHashFunc * numHashTables).map(i => (i,Hasher(m)))


   // val broascastHashFunctions: Broadcast[IndexedSeq[(Hasher, Int)]] = sc.broadcast(hashFunctions)

    val broascastHashFunctions: Broadcast[IndexedSeq[(Int, Hasher)]] = sc.broadcast(hashFunctions)


    val dataRDD = data.cache()

    val hashTables: RDD[((Int, String), (Long,SparseVector))] = dataRDD.flatMap {
      case (id, sparseVector) =>
        hashVector(sparseVector, broascastHashFunctions).map((_,(id,sparseVector)))
    }.partitionBy(new BucketPartitioner(numHashTables)).cache()


    new LSHModelWithData(m,numHashFunc,numHashTables,hashFunctions,hashTables)


  }




}

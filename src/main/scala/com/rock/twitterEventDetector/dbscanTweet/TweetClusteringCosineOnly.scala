



package com.rock.twitterEventDetector.dbscanTweet

import java.util.Date
import java.util.concurrent.Executors
import com.mongodb.casbah.MongoConnection

import com.mongodb.BasicDBObject
 import com.mongodb.casbah.commons.MongoDBObject
import com.mongodb.casbah.{MongoClientURI, MongoClient}
import com.rock.twitterEventDetector.configuration.Constant._
import com.rock.twitterEventDetector.db.mongodb.sparkMongoIntegration.SparkMongoIntegration
import com.rock.twitterEventDetector.db.mongodb.{DbpediaCollection, DbpediaAnnotationCollection, TweetCollection}
 import com.rock.twitterEventDetector.dbscanTweet.Distances._
 import com.rock.twitterEventDetector.lsh.{LSHWithData, LSHModelWithData, LSH, LSHModel}
import com.rock.twitterEventDetector.model.Model
import com.rock.twitterEventDetector.model.Model._
import com.rock.twitterEventDetector.model.Tweets.{VectorTweet, AnnotatedTweet, AnnotatedTweetWithDbpediaResources, Tweet}
import com.rock.twitterEventDetector.nlp.DbpediaSpootLightAnnotator
import com.rock.twitterEventDetector.nlp.indexing.{AnalyzerUtils, MyAnalyzer}
import com.rock.twitterEventDetector.utils.ProprietiesConfig
import edu.berkeley.cs.amplab.spark.indexedrdd.IndexedRDD
import edu.berkeley.cs.amplab.spark.indexedrdd.IndexedRDD._
import org.apache.spark.broadcast.Broadcast
import org.apache.spark.graphx._
import org.apache.spark.mllib.feature.{HashingTF, IDF, Normalizer}
import org.apache.spark.mllib.linalg.{SparseVector, Vector}
import org.apache.spark.rdd.RDD
import org.apache.spark.storage.StorageLevel
import org.apache.spark.{Accumulable, SparkConf, SparkContext}
import org.apache.spark.mllib.clustering.{KMeans, KMeansModel}
import scala.Predef
import scala.annotation.tailrec
import scala.collection.JavaConverters._
import scala.collection.immutable.IndexedSeq
import com.rock.twitterEventDetector.utils.UtilsFunctions._
import scala.concurrent.duration.Duration
import scala.util.{Success, Failure}

import scala.collection.{Map, mutable}
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.Failure

/**
  * Created by rocco on 26/01/2016.
  */
object TweetClusteringCosineOnly {

  val NOISE: VertexId =(-1l)





  /**
    * Generate tf-idf vectors from the a rdd containing tweets
    *
    * @param tweets
    * @param sizeDictionary
    * @return
    */
  def generateTfIdfVectors(tweets: RDD[(Long, Tweet)], sizeDictionary: Int): RDD[(Long, SparseVector)] = {

    val hashingTF = new HashingTF(sizeDictionary)
    // Load documents (one per line).
    val tfVectors: RDD[(VertexId, Vector)] = tweets mapPartitions  {
      it => {
        val analyzer = new MyAnalyzer()
        it.flatMap { case (idtweet, tweet) =>

          /**
            * per far si che gli hashtag abbiano un boost pari a 2.0
            * è sufficiente appendere a fine del testo del tweet tutti gli hashtag
            * in questo modo avranno tf pari a 2.
            */
          val textToTokenize:String =tweet.text+" "+tweet.splittedHashTags.getOrElse("")+" "+tweet.hashTags.mkString(" ");
          val tokenList = AnalyzerUtils.tokenizeText(analyzer, textToTokenize).asScala

          if (tokenList.size >= 2) {
            Some(idtweet, hashingTF.transform(tokenList))

          }
          else None


        }


      }


    }

    tfVectors.cache()
    val idf = new IDF(2).fit(tfVectors.values)

    val norm: Normalizer = new Normalizer()
    val tfidf: RDD[(Long, SparseVector)] = tfVectors.map {
      tuple => {

        (tuple._1, norm.transform(idf.transform(tuple._2)).toSparse)
      }
    }
    tfidf
  }


  /**
    * Generate tf-idf vectors from the a rdd containing tweets
    *
    * @param tweets
    * @param sizeDictionary
    * @return
    */
  def generateTfIdfVectors2(tweets: RDD[(Long, Tweet)], sizeDictionary: Int): RDD[Vector] = {

    val hashingTF = new HashingTF(sizeDictionary)
    // Load documents (one per line).
    val tfVectors: RDD[(VertexId, Vector)] = tweets mapPartitions  {
      it => {
        val analyzer = new MyAnalyzer()
        it.flatMap { case (idtweet, tweet) =>

          /**
            * per far si che gli hashtag abbiano un boost pari a 2.0
            * è sufficiente appendere a fine del testo del tweet tutti gli hashtag
            * in questo modo avranno tf pari a 2.
            */
          val textToTokenize:String =tweet.text+" "+tweet.splittedHashTags.getOrElse("")+" "+tweet.hashTags.mkString(" ");
          val tokenList = AnalyzerUtils.tokenizeText(analyzer, textToTokenize).asScala

          if (tokenList.size >= 2) {
            Some(idtweet, hashingTF.transform(tokenList))

          }
          else None


        }


      }


    }

    tfVectors.cache()
    val idf = new IDF(2).fit(tfVectors.values)

    val norm: Normalizer = new Normalizer()
    val tfidf = tfVectors.map {
      tuple => {

        (idf.transform(tuple._2))
      }
    }
    tfidf
  }
  /**
    * Generate tf-idf vectors from the a rdd containing tweets
    *
    * @param tweets
    * @param sizeDictionary
    * @return
    */
  def nlpPipeLine(tweets: RDD[(Long, Tweet)], sizeDictionary: Int): RDD[(Long, VectorTweet)] = {

    val hashingTF = new HashingTF(sizeDictionary)
    // Load documents (one per line).
    val tfVectors: RDD[(VertexId, Tweet,Vector)] = tweets mapPartitions {
      it => {
        val analyzer = new MyAnalyzer()
        it.flatMap { case (idtweet, tweet) =>

          /**
            * per far si che gli hashtag abbiano un boost pari a 2.0
            * è sufficiente appendere a fine del testo del tweet tutti gli hashtag
            * in questo modo avranno tf pari a 2.
            */
          val textToTokenize:String =tweet.text+" "+tweet.splittedHashTags.getOrElse("")+" "+tweet.hashTags.mkString(" ");

          val tokenList = AnalyzerUtils.tokenizeText(analyzer, textToTokenize).asScala

          if (tokenList.size >= 2) {

            Some(idtweet, tweet, hashingTF.transform(tokenList))
          }
          else None

        }
      }
    }

    tfVectors.cache()

    val idf = new IDF(2).fit(tfVectors.map(x=>x._3))
    val annotatedTweet = tfVectors.map (tuple =>
      (tuple._1,  new VectorTweet(tuple._2 ,idf.transform(tuple._3).toSparse)))
    annotatedTweet
  }



  def clusterCartesian(sc: SparkContext,
                       dataToCluster:RDD[(Long,Tweet)],
                       minPts: Int,
                       eps: Double,
                       dicSizePow:Int=19)={

  val dicSize=Math.pow(2,dicSizePow).toInt

    val tfIdfVecs=generateTfIdfVectors(dataToCluster,dicSize)
   val  candidatesNeighbors: RDD[(VertexId, VertexId)] =tfIdfVecs.cartesian(tfIdfVecs).map{
      case(((ida,va),(idb,vb)))=>((ida,idb),1d-cosine(va,vb))
    }.filter{
      case(_,distance)=>distance<=eps
    }.flatMap{
      case((ida,idb),_)=>List((ida,idb),(idb,ida))
    }
    val zeroSetElem = collection.mutable.HashSet.empty[Long]

    candidatesNeighbors.aggregateByKey(zeroSetElem)(
      (set, id) => set+=id,
      (set1, set2) => set1 ++ set2)
      .filter{
        case (_, set) => set.size>=minPts
      }

    val filteredneighList =  candidatesNeighbors
      .aggregateByKey(zeroSetElem)(
        (set, id) => set+=id,
        (set1, set2) => set1 ++ set2)
      .filter{
        case (_, set) => set.size>=minPts
      }.flatMap {
      case (idCore, listNeighbor) => listNeighbor map ( neighbor => (idCore, neighbor))
    }.persist(StorageLevel.DISK_ONLY)


    val graph: Graph[Int, Int] = Graph.fromEdgeTuples(filteredneighList, 1,None,StorageLevel.MEMORY_AND_DISK)
    val connectedComponents: VertexRDD[VertexId] = graph.connectedComponents().vertices;


    val clusteredData: RDD[(VertexId, VertexId)] =
      dataToCluster.leftOuterJoin(connectedComponents)
        .map{
          case(objectId,(instance,Some(clusterId)))=>(objectId,clusterId)
          case(objectId,(instance,None))=>(objectId,NOISE)
        }


    clusteredData


  }



  /**
    *
    * @param sparkContext
    * @param neighRDD
    * @return
    */
  private def constructEdgesFromSimilarities(sparkContext: SparkContext,minPts:Int,neighRDD:RDD[(Long,Long)]): RDD[(VertexId, VertexId)] = {
    val zeroSetElem = collection.mutable.HashSet.empty[Long]


    /**
      *
      * Per ogni id a raggruppo in un insieme gli id degli oggetti nel viciniato di a
      *
      */
    val grouped = neighRDD.aggregateByKey(zeroSetElem)(
      (set, id) => set += id,
      (set1, set2) => set1 ++ set2).cache(
    )//.persist(StorageLevel.MEMORY_AND_DISK)



    val coreObjects = grouped.filter {
      case (_, set) => set.size >= minPts
    }.persist(StorageLevel.MEMORY_AND_DISK)

    val nonCoreObjects = grouped.filter {
      case (_, set) => set.size < minPts
    }

    val coreIds = coreObjects.keys.collect().toSet
    val broadCastIds = sparkContext.broadcast(coreIds)


    val coreSxDX: RDD[(VertexId, VertexId)] = coreObjects.flatMap {
      case (coreId, neighbors) =>
        val coreNeighbors = neighbors.filter(x => broadCastIds.value.contains(x) && coreId< x).toSeq
        coreNeighbors.map(x => (coreId, x))
    }

    val coreSxnonDX =nonCoreObjects.flatMap{
      case(nonCoreId,neighbhors)=>
        val core=neighbhors.toStream.find(x => broadCastIds.value.contains(x))
        core match {
          case Some(idCore)=>Some((idCore,nonCoreId))
          case None=>None
        }
    }
    coreSxDX.union(coreSxnonDX)


  }




  /**
    *
    * @param minPts
    * @param eps
    */
  def clusteringTweets(sc: SparkContext,
                       dataToCluster:RDD[(Long,Tweet)],
                       lshModel: LSHModelWithData,
                       minPts: Int,
                       eps: Double): RDD[(VertexId, VertexId)] = {


    /**
      * aggrego gli oggetti nello stesso bucket (idbanda-signature)
      */
    val zeroElem = collection.mutable.LinkedList.empty[(Long,SparseVector)]

    val groupedLSH: RDD[((Int, String), mutable.LinkedList[(Long, SparseVector)])] =lshModel.hashTables.aggregateByKey(zeroElem)(
      (list: collection.mutable.LinkedList[(Long,SparseVector)], v:(Long,SparseVector)) => list :+ v ,
      (list1, list2) => list1 ++ list2)

    groupedLSH.persist(StorageLevel.MEMORY_AND_DISK)




    val candidatesNeighbors: RDD[(VertexId, VertexId)] = groupedLSH.flatMapValues{
      case(listCandidateNeighbors:mutable.LinkedList[(VertexId, SparseVector)])=>generateCouplesFromLinkedList(listCandidateNeighbors)
    }
      .filter{
        case(_,((id1,v1),(id2,v2)))=> 1d-cosine(v1,v2)<=eps
      }.flatMap{
      case (_,((id1,v1),(id2,v2))) =>List((id1,id2),(id2,id1))
    }.distinct().persist(StorageLevel.MEMORY_AND_DISK)




    val filteredneighList=constructEdgesFromSimilarities(sc,minPts,candidatesNeighbors)

    //println("DATA COUNT "+data.count())



    val graph: Graph[Int, Int] = Graph.fromEdgeTuples(filteredneighList, 1,None,StorageLevel.MEMORY_AND_DISK)
    val connectedComponents: VertexRDD[VertexId] = graph.connectedComponents().vertices;


    val clusteredData: RDD[(VertexId, VertexId)] =
      dataToCluster.leftOuterJoin(connectedComponents)
        .map{
          case(objectId,(instance,Some(clusterId)))=>(objectId,clusterId)
          case(objectId,(instance,None))=>(objectId,NOISE)
        }


    clusteredData



  }

  /**
    *
     * @param uris
    * @return
    */
  def getInLinkFromUris(uris: List[String],client:MongoClient,accumulatorInLinks:scala.collection.mutable.Map[String, Set[Int]]): Set[Int] = {
              val inLinks: List[Set[Int]] = uris.flatMap{
                uri=>
                    val name= uri.substring(28,uri.length)
                  if(accumulatorInLinks.contains(name))
                    accumulatorInLinks.get(name)
                  else{
                    val optionInLinkSet=DbpediaCollection.findDbpediaResourceInLinks(name,client)
                    optionInLinkSet match{
                     case Some(set)=>    accumulatorInLinks.put(name,set)
                     case None=>accumulatorInLinks.put(name,Set.empty[Int])
                    }
                    optionInLinkSet
                  }


               }

              inLinks.foldLeft(Set.empty[Int])((r,c) =>r ++ c)

  }






  /**
    * retrive the annotations of tweet
    * it will reurn Some(of the list made of Dbpedia Annotations object]
    * None if the tweet isn't altready annotated through dbpedia Spootlight
    *
    * @param idTweet
    * @return
    */
  def getUrisDbpediaFromIdtweet(idTweet:Long,client:  MongoClient): Option[List[String]] = {


    val collection = client(MONGO_DB_NAME)("annotazioniSpark")
    val result = collection.findOne(MongoDBObject("_id" -> idTweet))
    result match {
      case Some(obj) =>

        val annotations: List[BasicDBObject] = obj.get("value").asInstanceOf[java.util.List[BasicDBObject]].asScala.toList
        val uris = annotations.map {
          ann =>
            ann.getString("uriDBpedia")
        }.toSet


        Some(uris.toList)


      case None => None
    }
  }


  def calculateSemanticSim(id1: VertexId, id2: VertexId, broadcastMapuris: Broadcast[Predef.Map[VertexId, Set[String]]], broadCastInLinks: Broadcast[Predef.Map[String, Set[Int]]]):Double = {
    val uriAOption=broadcastMapuris.value.get(id1)
    val uriBOption=broadcastMapuris.value.get(id2)
    if(uriAOption.isDefined && uriBOption.isDefined){
        val uriA: Set[String] =uriAOption.get
        val uriB=uriBOption.get
        val inLinksA=uriA.flatMap(uri=>broadCastInLinks.value.get(uri)).foldLeft(Set.empty[Int])((r,c)=>r++c)
        val inLinksB=uriB.flatMap(uri=>broadCastInLinks.value.get(uri)).foldLeft(Set.empty[Int])((r,c)=>r++c)
        calculateNormalizedGoogleDistanceInt(inLinksA,inLinksB)

    }else 0.0
  }

  /**
    *
    * @param minPts
    * @param eps
    */
  def clusteringTweetsSemantic(sc: SparkContext,
                       dataToCluster:RDD[(Long,Tweet)],
                       lshModel: LSHModelWithData,
                       minPts: Int,
                       eps: Double) = {

    val ids: Iterator[Predef.Map[VertexId, Set[String]]] =dataToCluster.keys.collect().grouped(1000).map(idTweets=>DbpediaAnnotationCollection.getUrisDbpediaOfTweets(idTweets))
    val tweetUriDBpedia: Predef.Map[VertexId, Set[String]] =ids.foldLeft(Map.empty[VertexId,Set[String]])((r, c) =>r ++ c)

    val allUriDbpedia=tweetUriDBpedia.values.foldLeft(Set.empty[String])((r, c) =>r ++ c)
   val uriInLinksIterator= allUriDbpedia.grouped(100).map(urisIterator=>DbpediaCollection.findDbpediaResourceInLinks(urisIterator))

    val uriInLinks: Predef.Map[String, Set[Int]] =uriInLinksIterator.foldLeft(Map.empty[String,Set[Int]])((r, c) =>r ++ c)

    val broadcastMapuris=sc.broadcast(tweetUriDBpedia)
    val broadCastInLinks=sc.broadcast(uriInLinks)
    /**
      * aggrego gli oggetti nello stesso bucket (idbanda-signature)
      */
    val zeroElem = collection.mutable.LinkedList.empty[(Long,SparseVector)]

    val groupedLSH: RDD[((Int, String), mutable.LinkedList[(Long, SparseVector)])] =lshModel.hashTables.aggregateByKey(zeroElem)(
      (list: collection.mutable.LinkedList[(Long,SparseVector)], v:(Long,SparseVector)) => list :+ v ,
      (list1, list2) => list1 ++ list2)

    groupedLSH.persist(StorageLevel.MEMORY_AND_DISK)




    val candidatesNeighbors: RDD[(VertexId, VertexId)] = groupedLSH.flatMapValues{
      case(listCandidateNeighbors:mutable.LinkedList[(VertexId, SparseVector)])=>generateCouplesFromLinkedList(listCandidateNeighbors)
    }
      .filter{
        case(_,((id1,v1),(id2,v2)))=>
          val textSim=cosine(v1,v2)
          val semanticSim=calculateSemanticSim(id1,id2,broadcastMapuris,broadCastInLinks)

          val sim=(textSim+semanticSim)/2.0


          1d-sim<=eps
      }.flatMap{
      case (_,((id1,v1),(id2,v2))) =>List((id1,id2),(id2,id1))
    }.distinct().persist(StorageLevel.MEMORY_AND_DISK)




    val filteredneighList=constructEdgesFromSimilarities(sc,minPts,candidatesNeighbors)

    //println("DATA COUNT "+data.count())



    val graph: Graph[Int, Int] = Graph.fromEdgeTuples(filteredneighList, 1,None,StorageLevel.MEMORY_AND_DISK)
    val connectedComponents: VertexRDD[VertexId] = graph.connectedComponents().vertices;


    val clusteredData: RDD[(VertexId, VertexId)] =
      dataToCluster.leftOuterJoin(connectedComponents)
        .map{
          case(objectId,(instance,Some(clusterId)))=>(objectId,clusterId)
          case(objectId,(instance,None))=>(objectId,NOISE)
        }


    clusteredData



  }

  def evaluateLSHModel(lshModel:LSHModel,data: RDD[(VertexId, SparseVector)]): (Double, Double, Double, Double) ={


    /**
      *a partire dal modello lsh
      * creo un indexed rdd che ad ogni id documento
      * associa un interable di coppie (banda,signature)
      */
    val invertedLsh: RDD[(VertexId, String)] =lshModel.hashTables.map{
      case(hashkey,id)=>(id,hashkey)
    }.groupByKey().map{
      case(id,listHash:Iterable[(Int,String)])=>{
        val sortedHashes: List[(Int, String)] =listHash.toList.sortBy(_._1)
        val signature= sortedHashes.foldLeft("")((b,a) => b+a)
        //val boolanSig: IndexedSeq[Boolean] =signature.map(x=>if (x=='0') false else true)

        (id,signature)
        //  val c=sortedHashes.foldRight()
      }
    }
    // implicit val serializer=new Tuple2Serializer[Int,String]
    // implicit val serializer2=new Tuple2Serializer[Long,Iterable[(Int,String)]]

    val indexedInvertedLsh: IndexedRDD[VertexId, String] = IndexedRDD(invertedLsh).cache()

    // Calculate a sum of set bits of XOR'ed bytes
    def hammingDistance(b1:String, b2: String):Double = {
      ((b1 zip b2) count ( x => x._1 != x._2 )).toDouble/b1.length.toDouble
    }





    val cosineSim=data.cartesian(data).filter{
      case((id1,_),(id2,_))=>id1<id2
    }.map {
      case ((id1, v1), (id2, v2)) => {
        //val cosineSim=cosine(v1,v2)
        //if(cosineSim>0.0) Some((id1,id2,1-cosineSim))
        // else None
        (id1,id2,exacteCosine(v1,v2))

      }
    }.collect()
    val errors=cosineSim.map{
      case(ida,idb,cosineDistance)=>{
        val signatureA =indexedInvertedLsh.get(ida).get
        val signatureB=indexedInvertedLsh.get(idb).get
        val hamm=hammingDistance(signatureA,signatureB)
        val approximateCosine: Double =hammingDistance( signatureA,signatureB)
        val error=math.abs((cosineDistance-approximateCosine))/cosineDistance
        println ((ida,idb)+ "COSINE DISTANCE "+cosineDistance+ " approx "+approximateCosine+" ERROR :"+error)
        error
      }
    }


    val avg=errors.reduce(_+_)/errors.length
    val dev=Math.sqrt(errors.map(x=>Math.pow(x-avg,2d)).reduce(_+_)/errors.length)
    println(" AVG ERROR  "+ avg + " MAX ERROR "+errors.max+" STD DEV "+dev)

    (avg,errors.min,errors.max,dev)
    //cosineSim.foreach(println)
  }




  def main(args: Array[String]) {




    //  val c= (1 to 500000).par.map(x=>if(x%2==0) '1' else '0').toString()
    //   print(c.par.map(x=>if(x=='0') false else true).toVector)

    val sparkConf = new SparkConf()
      .setMaster("local[*]")
      .setAppName("annotations")
      .set("spark.executor.memory ", "14g")
      .set("spark.local.dir", "/tmp/spark-temp");
    val sc = new SparkContext(sparkConf)




    val minDAte = TweetCollection.findMinMaxValueDate()
    println("min date value" + minDAte)

    val maxDate = new Date(minDAte.getTime + 1000000)
    //val tweetsfirst72H: RDD[(VertexId, Tweet)] = SparkMongoIntegration.getTweetsAsRDDInTimeInterval(sc, minDAte,maxDate)
    val relevantTweets=SparkMongoIntegration.getTweetsAsTupleRDD(sc,None,"onlyRelevantTweets")

    //  val tentweets=tweetsfirst72H.take(10);
    relevantTweets.cache()
    println(" NUMBER Of tweeets" + relevantTweets.count())

    println("finished loading tweets from mongo")
    /**
      * primo passo da fare è generare
      * per ogni tweet vettori di hashingTF
      */
    val sizeDictionary=Math.pow(2, 19).toInt
    val tfidfVectors: RDD[(VertexId, SparseVector)] = generateTfIdfVectors(relevantTweets, sizeDictionary)

    val vectors=generateTfIdfVectors2(relevantTweets, sizeDictionary)

    // Cluster the data into two classes using KMeans
    val numClusters = 500
    val numIterations = 20
    val clusters = KMeans.train(vectors, numClusters, numIterations)
    // Evaluate clustering by computing Within Set Sum of Squared Errors
    val WSSSE = clusters.computeCost(vectors)
    println("Within Set Sum of Squared Errors = " + WSSSE)

    // Save and load model
    clusters.save(sc, "target/kmeansModel")



    val lsh = new LSHWithData(tfidfVectors, sizeDictionary, numHashFunc  =30, numHashTables = 10)
    val lshModel = lsh.run(sc)
    //lshModel.save(sc, "target/relevantTweetsLSH")

    //val lshModel: LSHModel= LSHModel.load(sc, "target/relevantTweetsLSH")

    println("finished loading lsh model")
    val clusteredData=clusteringTweets(sc,relevantTweets,lshModel,50,0.35)

    clusteredData.groupByKey().map(x=>(x._1,x._2.size)).collect().foreach(println)

  }

}


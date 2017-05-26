package com.jsl.nlp.annotators.pos.perceptron

import com.jsl.nlp.annotators.pos.POSApproach
import com.typesafe.scalalogging.Logger
import org.slf4j.LoggerFactory

import scala.collection.mutable.{Map => MMap}
import scala.util.Random

/**
  * Created by Saif Addin on 5/17/2017.
  * Inspired on Averaged Perceptron by Matthew Honnibal
  * https://explosion.ai/blog/part-of-speech-pos-tagger-in-python
  */
class PerceptronApproach(trainedModel: AveragedPerceptron) extends POSApproach {

  import PerceptronApproach._

  override val description: String = "Averaged Perceptron tagger, iterative average weights upon training"

  /**
    * Bundles a sentence within context and then finds unambiguous word or predict it
    * @return
    */

  override val model: AveragedPerceptron = trainedModel

  override def tag(rawSentences: Array[String]): Array[Array[TaggedWord]] = {
    val sentences = rawSentences.map(Sentence)
    var prev = START(0)
    var prev2 = START(1)
    sentences.map(_.tokenize).map{words => {
      val context: Array[String] = START ++: words.map(normalized) ++: END
      words.zipWithIndex.map{case (word, i) =>
        val tag = model.taggedWordBook.find(_.word == word.toLowerCase).map(_.tag).getOrElse(
          {
            val features = getFeatures(i, word, context, prev, prev2)
            model.predict(features.toMap)
          }
        )
        prev2 = prev
        prev = tag
        (word, tag)
      }
    }}.map(_.map(taggedWord => TaggedWord(taggedWord._1, taggedWord._2)))
  }

}
object PerceptronApproach {

  private type TaggedSentences = List[(List[String], List[String])]

  private val START = Array("-START-", "-START2-")
  private val END = Array("-END-", "-END2-")

  val logger = Logger(LoggerFactory.getLogger("PerceptronTraining"))

  private def normalized(word: String): String = {
    if (word.contains("-") && word.head != '-') {
      "!HYPEN"
    } else if (word.forall(_.isDigit) && word.length == 4) {
      "!YEAR"
    } else if (word.head.isDigit) {
      "!DIGITS"
    } else {
      word.toLowerCase
    }
  }

  /**
    * Method used when a word tag is not  certain. the word context is explored and features collected
    * @param init
    * @param word
    * @param context
    * @param prev holds previous tag
    * @param prev2 holds previous tag
    * @return
    */
  private def getFeatures(
                           init: Int,
                           word: String,
                           context: Array[String],
                           prev: String,
                           prev2: String
                         ): MMap[String, Int] = {
    val features = MMap[String, Int]().withDefaultValue(0)
    def add(name: String, args: Array[String] = Array()): Unit = {
      features((name +: args).mkString(" ")) += 1
    }
    val i = init + START.length
    add("bias")
    add("i suffix", Array(word.takeRight(3)))
    add("i pref1", Array(word.head.toString))
    add("i-1 tag", Array(prev))
    add("i-2 tag", Array(prev2))
    add("i tag+i-2 tag", Array(prev, prev2))
    add("i word", Array(context(i)))
    add("i-1 tag+i word", Array(prev, context(i)))
    add("i-1 word", Array(context(i-1)))
    add("i-1 suffix", Array(context(i-1).takeRight(3)))
    add("i-2 word", Array(context(i-2)))
    add("i+1 word", Array(context(i+1)))
    add("i+1 suffix", Array(context(i+1).takeRight(3)))
    add("i+2 word", Array(context(i+2)))
    features
  }

  /**
    * Supposed to find very frequent tags and record them
    * @param taggedSentences
    */
  private def buildTagBook(
                            taggedSentences: List[TaggedSentence],
                            frequencyThreshold: Int = 20,
                            ambiguityThreshold: Double = 0.97
                          ): List[TaggedWord] = {
    /**
      * This creates counts, a map of words that refer to all possible tags and how many times they appear
      * It holds how many times a word-tag combination appears in the training corpus
      * It is also used in the rest of the tagging process to hold tags
      * It also stores the tag in classes which holds tags
      * Then Find the most frequent tag and its count
      * If there is a very frequent tag, map the word to such tag to disambiguate
      */

    val tagFrequenciesByWord = taggedSentences
      .flatMap(_.tagged)
      .groupBy(_.word.toLowerCase)
      .mapValues(_.groupBy(_.tag).mapValues(_.length))

    tagFrequenciesByWord.filter{case (_, tagFrequencies) =>
        val (_, mode) = tagFrequencies.maxBy(_._2)
        val n = tagFrequencies.values.sum
        n >= frequencyThreshold && (mode / n.toDouble) >= ambiguityThreshold
      }.map{case (word, tagFrequencies) =>
        val (tag, _) = tagFrequencies.maxBy(_._2)
        logger.debug(s"TRAINING: Ambiguity discarded on: << $word >> set to: << $tag >>")
        TaggedWord(word, tag)
      }.toList
  }

  def train(rawTaggedSentences: TaggedSentences, nIterations: Int = 5): PerceptronApproach = {
    /**
      * Generates TagBook, which holds all the word to tags mapping
      * Adds the found tags to the tags available in the model
      */
    val taggedSentence = rawTaggedSentences.map(s => TaggedSentence(s._1, s._2))
    val taggedWordBook = buildTagBook(taggedSentence)
    val classes = taggedSentence.flatMap(_.tags).distinct
    val initialModel = new AveragedPerceptron(taggedWordBook, classes, MMap())
    /**
      * Iterates for training
      */
    val trainedModel = (1 to nIterations).foldRight(initialModel){(iteration, iteratedModel) => {
      logger.debug(s"TRAINING: Iteration n: $iteration")
      /**
        * Defines a sentence context, with room to for look back
        */
      var prev = START(0)
      var prev2 = START(1)
      /**
        * In a shuffled sentences list, try to find tag of the word, hold the correct answer
        */
      Random.shuffle(taggedSentence).foldRight(iteratedModel)
      {(taggedSentence, model) =>
        val context = START ++: taggedSentence.words.map(w => normalized(w)) ++: END
        taggedSentence.words.zipWithIndex.foreach{case (word, i) =>
          val guess = model.taggedWordBook.find(_.word == word.toLowerCase).map(_.tag).getOrElse({
            /**
              * if word is not found, collect its features which are used for prediction and predict
              */
            val features = getFeatures(i, word, context, prev, prev2)
            val guess = model.predict(features.toMap)
            /**
              * Update the model based on the prediction results
              */
            model.update(taggedSentence.tags(i), guess, features.toMap)
            /**
              * return the guess
              */
            guess
          })
          /**
            * shift the context
            */
          prev2 = prev
          prev = guess
        }
        model
      }.averagedModel
    }}
    logger.debug("TRAINING: Finished all iterations")
    new PerceptronApproach(trainedModel)
  }
}
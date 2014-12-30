/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark

import org.apache.spark.annotation.DeveloperApi
import org.apache.spark.util.collection.{AppendOnlyMap, ExternalAppendOnlyMap}

/**
 * :: DeveloperApi ::
 * A set of functions used to aggregate data.
 *
 * @param createCombiner function to create the initial value of the aggregation.
 * @param mergeValue     function to merge a new value into the aggregation result.
 * @param mergeCombiners function to merge outputs from multiple mergeValue function.
 */
@DeveloperApi
case class Aggregator[K, V, C] (
  createCombiner: V      => C,
  mergeValue    : (C, V) => C,
  mergeCombiners: (C, C) => C) {

  /**
   * The following is an outline of the responsibilities of this class, it explains what this
   * class does, so it is concise and short, you can get an idea of what this class does
   * without going into too much details.
   */

  @deprecated("use combineValuesByKey with TaskContext argument", "0.9.0")
  def combineValuesByKey(iterator: Iterator[_ <: Product2[K, V]]): Iterator[(K, C)] =
    combineValuesByKey(iterator, null)

  /**
   * This is to call combineValues function with the parameter iter and context.
   *
   * @param iterator iterator
   * @param context  context
   * @return         a combined iterator
   */
  def combineValuesByKey(iterator: Iterator[_ <: Product2[K, V]],
                         context : TaskContext): Iterator[(K, C)] =
    combineValues(iterator, context)

  @deprecated("use combineCombinersByKey with TaskContext argument", "0.9.0")
  def combineCombinersByKey(iter: Iterator[_ <: Product2[K, C]]): Iterator[(K, C)] =
    combineCombinersByKey(iter, null)

  /**
   * This is to call combineCombiners function with the parameter iter and context.
   *
   * @param iterator iterator
   * @param context  context
   * @return         a combined iterator
   */
  def combineCombinersByKey(iterator: Iterator[_ <: Product2[K, C]],
                            context : TaskContext): Iterator[(K, C)] =
    combineCombiners(iterator, context)

  /**
   * The following are the details of this class, it explains how this class works internally,
   * so if you are working on changing the behaviour of this class, you can dig into the
   * details now, otherwise you can skip reading them.
   */

  /**
   * When spilling is enabled sorting will happen externally, but not necessarily with an
   * ExternalSorter.
   * Instead of remember a boolean variable, just use a lazy val to remember some functions
   * for the cases of spilling is enabled and disabled. This is a lazy val so it will be
   * evaluated only when needed and only evaluated once. The variable is in tuple form so
   * it can have meaningful names for the values inside the tuple to avoid using ._1 and ._2
   * to get the value from a single tuple variable.
   *
   * This tuple variable are the two functions used to combine values or combine combiners
   * when spilling is enabled or disabled respectively.
   */
  private lazy val (combineValues, combineCombiners) =
    if (SparkEnv.get.conf.getBoolean("spark.shuffle.spill", defaultValue = true)) {
      (whenSpillEnabled[V](combineValue) _,
        whenSpillEnabled[C](combineCombiner) _)
    } else {
      (whenSpillDisabled[V](valueCombiners _, insertAll) _,
        whenSpillDisabled[C](combinerMerger, insert) _)
    }

  /**
   * This method is shared by both combineValuesByKey and combineCombinersByKey method since
   * some behaviours are abstracted into functions so they can used as parameter to alter
   * the behaviour of the logic flow. This is a typical application of functional programming
   * paradigm and it allows more abstraction which can't be achieved by using Object Oriented
   * Paradigm.
   *
   * @param change   function to be executed by foreach method
   * @param iterator iterator, which is implicit so the caller doesn't need to pass the parameter
   *                 explicitly
   * @return         the processed iterator
   */
  def whenSpillEnabled[A](change  : AppendOnlyMap[K, C] => Product2[K, A] => C)
                         (iterator: Iterator[_ <: Product2[K, A]],
                          context : TaskContext): Iterator[(K, C)] = {
    val combiners = new AppendOnlyMap[K, C]
    iterator.foreach { change(combiners) }
    combiners.iterator
  }

  /**
   * This method is shared by both combineValuesByKey and combineCombinersByKey method since
   * some behaviours are abstracted into functions so they can used as parameter to alter the
   * behaviour of the logic flow. This is a typical application of functional programming
   * paradigm and it allows more abstraction which can't be achieved by using Object Oriented
   * Paradigm.
   *
   * @param create   function to create combiners
   * @param insert   function to insert the iterator into combiners using different approach
   * @param iterator iterator, which is implicit so the caller doesn't need to pass the parameter
   *                 explicitly
   * @param context  context , which is implicit so the caller doesn't need to pass the parameter
   *                 explicitly
   * @return         the combined iterator
   */
  def whenSpillDisabled[A](create  : () => ExternalAppendOnlyMap[K, A, C],
                           insert  : InsertIteratorToCombiners[A])
                          (iterator: Iterator[_ <: Product2[K, A]],
                           context : TaskContext): Iterator[(K, C)] = {
    val combiners = create()
    insert(iterator, combiners)
    // Update task metrics if context is not null
    // TODO: Make context non optional in a future release
    Option(context).foreach { c =>
      c.taskMetrics.memoryBytesSpilled += combiners.memoryBytesSpilled
      c.taskMetrics.diskBytesSpilled   += combiners.diskBytesSpilled
    }
    combiners.iterator
  }

  /**
   * lazy val can't achieve the same effect. So define it as a method but is is used as a
   * "Partially Applied Function" to be passed to method as a parameter. This method doesn't
   * have () so you need to use combinersV _ to indicate it is a "Partially Applied Function"
   * in combineValuesByKey, please compare combinersC() method.
   *
   * @return the function to create a ExternalAppendOnlyMap
   */
  def valueCombiners =
    new ExternalAppendOnlyMap[K, V, C](createCombiner, mergeValue, mergeCombiners)

  /**
   * lazy val can't achieve the same effect. So define it as a method but is is used as a
   * "Partially Applied Function" to be passed to method as a parameter. This method ends
   * with () so you can use its name without () to indicate it is a "Partially Applied
   * Function" in combineCombinersByKey, please compare combinersV method.
   *
   * @return the function to create a ExternalAppendOnlyMap
   */
  def combinerMerger() =
    new ExternalAppendOnlyMap[K, C, C](identity, mergeCombiners, mergeCombiners)

  /**
   *
   * This when calling valueMerging(v), it returns another function which is used as the
   * parameter of this method call,
   * {{{
      combiners.changeValue(k, valueMerging(v))
   * }}}
   *
   * @return  function of another function
   */
  def valueMerging: V => (Boolean, C) => C = v => {
    case (hadValue, oldValue) =>
      if (hadValue) mergeValue(oldValue, v) else createCombiner(v)
  }


  /**
   * This will be used as a "Partially Applied Function" as combinerMerging(c)
   *
   * @param c        combiner
   * @param hadValue had value
   * @param oldValue old value
   * @return         the result of the function call
   */
  def combinerMerging(c: C)(hadValue: Boolean, oldValue: C) =
    if (hadValue) mergeCombiners(oldValue, c) else c

  /**
   * Call this method with only one parameter to get a function and pass it as a parameter to
   * changeValue method of combiners, and use that as the function to be executed by the
   * foreach method of the iterator. This method illustrates the Higher Order Function
   * principle of function programming.
   *
   * Please compare combinerMerging which is in another style.
   *
   * case(k, v) extracts a tuple into two meaningful parameter tuple to avoid
   * using kv._1, kv._2
   * @return    a function to be executed by the foreach method of the iterator
   */
  def combineValue: AppendOnlyMap[K, C] => Product2[K, V] => C = combiners => {
    case (k, v) => combiners.changeValue(k, valueMerging(v))
  }


  /**
   * Call this method with only one parameter to get a "Partially Applied Function" and pass
   * it as a parameter to changeValue method of combiners, and use that as the function to
   * be executed by the foreach method of the iterator. This method illustrates the Higher
   * Order Function principle of function programming.
   *
   * Please compare combineValue which is in another style.
   *
   * case(k, c) extracts a tuple into two meaningful parameter tuple to avoid
   * using kc._1, kc._2
   *
   * @param combiners combiners
   * @return          a function to be executed by the foreach method of the iterator
   */
  def combineCombiner(combiners: AppendOnlyMap[K, C]): Product2[K, C] => C = {
    case (k, c) => combiners.changeValue(k, combinerMerging(c))
  }

  /**
   *
   * Call the combiners.insertAll(iterator) method. This is a method but it is used
   * as a Partially Applied Function by combineValuesByKey method as a parameter to call
   * shared combineVByKey method.
   *
   * @param iterator  iterator
   * @param combiners combiners
   */
  def insertAll(iterator: Iterator[_ <: Product2[K, V]], combiners: ExternalAppendOnlyMap[K, V, C]) =
    combiners.insertAll(iterator)

  /**
   *
   * Call the combiners.insert(key, value) method when executing the foreach method of iterator.
   * This method illustrates the Higher Order Function principle of function programming.
   * Even it is a method, it is used as a "Partially Applied Function" by the definition of
   * {{{
        private lazy val (combineValues, combineCombiners)
   * }}}
   * as a parameter to call shared whenSpillDisabled method.
   *
   * @param iterator  iterator
   * @param combiners combiners
   */
  def insert(iterator: Iterator[_ <: Product2[K, C]], combiners: ExternalAppendOnlyMap[K, C, C]) =
    iterator.foreach { case (k, v) => combiners.insert(k, v)}

  /**
   * This type simplifies the method signature. This is the application of Scala type definition.
   * @tparam A type parameter
   */
  type InsertIteratorToCombiners[A] =
    (Iterator[_ <: Product2[K, A]], ExternalAppendOnlyMap[K, A, C]) => Unit
}

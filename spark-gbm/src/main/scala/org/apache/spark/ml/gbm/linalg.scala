package org.apache.spark.ml.gbm

import java.{util => ju}

import scala.collection.mutable
import scala.reflect.ClassTag
import scala.{specialized => spec}

trait KVVector[@spec(Byte, Short, Int) K, @spec(Byte, Short, Int, Long, Float, Double) V] extends Serializable {

  def len: Int

  def negate()
            (implicit cv: ClassTag[V], nuv: Numeric[V]): KVVector[K, V]

  def apply(index: Int)
           (implicit ink: Integral[K], nek: NumericExt[K],
            nuv: Numeric[V]): V

  def slice(sorted: Array[Int])
           (implicit ck: ClassTag[K], ink: Integral[K],
            cv: ClassTag[V], nuv: Numeric[V]): KVVector[K, V]

  def toArray()
             (implicit ink: Integral[K],
              cv: ClassTag[V], nuv: Numeric[V]): Array[V]

  def totalIter()
               (implicit ink: Integral[K],
                nuv: Numeric[V]): Iterator[(K, V)]

  def activeIter()
                (implicit ink: Integral[K],
                 nuv: Numeric[V]): Iterator[(K, V)]

  def reverseActiveIter()
                       (implicit ink: Integral[K],
                        nuv: Numeric[V]): Iterator[(K, V)]

  def nnz()
         (implicit nuv: Numeric[V]): Int

  def toDense()
             (implicit ink: Integral[K],
              cv: ClassTag[V], nuv: Numeric[V]): KVVector[K, V]

  def toSparse()
              (implicit ck: ClassTag[K], ink: Integral[K],
               cv: ClassTag[V], nuv: Numeric[V]): KVVector[K, V]

  def isDense: Boolean

  def isSparse: Boolean = !isDense

  def isEmpty: Boolean = len == 0

  def compressed()
                (implicit ck: ClassTag[K], ink: Integral[K], nek: NumericExt[K],
                 cv: ClassTag[V], nuv: Numeric[V], nev: NumericExt[V]): KVVector[K, V] = {
    if (nev.size * len + 8 <= (nek.size + nev.size) * nnz + 20) {
      toDense
    } else {
      toSparse
    }
  }

  def plus(index: K, value: V)
          (implicit ck: ClassTag[K], ink: Integral[K], nek: NumericExt[K],
           cv: ClassTag[V], nuv: Numeric[V]): KVVector[K, V]

  def minus(index: K, value: V)
           (implicit ck: ClassTag[K], ink: Integral[K], nek: NumericExt[K],
            cv: ClassTag[V], nuv: Numeric[V]): KVVector[K, V] = {
    plus(index, nuv.negate(value))
  }

  def plus(other: KVVector[K, V])
          (implicit ck: ClassTag[K], ink: Integral[K], nek: NumericExt[K],
           cv: ClassTag[V], nuv: Numeric[V], nev: NumericExt[V]): KVVector[K, V] = {
    import nuv._

    (this.isDense, other.isDense) match {
      case (true, true) =>
        val Seq(arr1, arr2) = Seq(toArray, other.toArray).sortBy(_.length)
        Iterator.range(0, arr1.length).foreach(i => arr2(i) += arr1(i))
        KVVector.dense[K, V](arr2).compressed

      case (true, false) =>
        val arr = if (len >= other.len) {
          toArray
        } else {
          toArray ++ Array.ofDim[V](other.len - len)
        }
        other.activeIter.foreach { case (k, v) => arr(ink.toInt(k)) += v }
        KVVector.dense[K, V](arr).compressed

      case (false, true) =>
        other.plus(this)

      case (false, false) =>
        val map = mutable.OpenHashMap.empty[K, V]
        activeIter.foreach { case (k, v) => map.update(k, v) }
        other.activeIter.foreach { case (k, v) =>
          val v2 = map.getOrElse(k, zero)
          map.update(k, v + v2)
        }

        val (indices, values) = map.toArray.sortBy(_._1).unzip
        val newLen = math.max(len, other.len)
        KVVector.sparse[K, V](newLen, indices, values).compressed
    }
  }

  def minus(other: KVVector[K, V])
           (implicit ck: ClassTag[K], ink: Integral[K], nek: NumericExt[K],
            cv: ClassTag[V], nuv: Numeric[V], nev: NumericExt[V]): KVVector[K, V] = {
    plus(other.negate)
  }
}

object KVVector {

  def empty[@spec(Byte, Short, Int) K, @spec(Byte, Short, Int, Long, Float, Double) V]()
                                                                                      (implicit cv: ClassTag[V], nev: NumericExt[V]): KVVector[K, V] = {
    dense[K, V](nev.emptyArray)
  }

  def dense[@spec(Byte, Short, Int) K, @spec(Byte, Short, Int, Long, Float, Double) V](values: Array[V]): KVVector[K, V] = {
    new DenseKVVector[K, V](values)
  }

  def sparse[@spec(Byte, Short, Int) K, @spec(Byte, Short, Int, Long, Float, Double) V](size: Int,
                                                                                        indices: Array[K],
                                                                                        values: Array[V]): KVVector[K, V] = {
    new SparseKVVector[K, V](size, indices, values)
  }
}


class DenseKVVector[@spec(Byte, Short, Int) K, @spec(Byte, Short, Int, Long, Float, Double) V](val values: Array[V]) extends KVVector[K, V] {

  override def len: Int = values.length

  override def negate()
                     (implicit cv: ClassTag[V],
                      nuv: Numeric[V]): KVVector[K, V] = {
    KVVector.dense[K, V](values.map(nuv.negate))
  }

  override def apply(index: Int)
                    (implicit ink: Integral[K], nek: NumericExt[K],
                     nuv: Numeric[V]) = values(index)

  override def plus(index: K, value: V)
                   (implicit ck: ClassTag[K], ink: Integral[K], nek: NumericExt[K],
                    cv: ClassTag[V], nuv: Numeric[V]): KVVector[K, V] = {
    require(ink.gteq(index, ink.zero))

    val newLen = math.max(ink.toInt(index) + 1, len)

    import nuv._
    if (value == zero) {
      if (len == newLen) {
        this
      } else {
        KVVector.dense[K, V](values ++ Array.fill(newLen - len)(zero))
      }

    } else {
      val i = ink.toInt(index)
      if (i < len) {
        values(i) += value
        this

      } else {
        val newValues = values ++ Array.fill(i - len + 1)(zero)
        newValues(i) = value
        KVVector.dense[K, V](newValues)
      }
    }
  }

  override def slice(sorted: Array[Int])
                    (implicit ck: ClassTag[K], ink: Integral[K],
                     cv: ClassTag[V], nuv: Numeric[V]): KVVector[K, V] =
    KVVector.dense(sorted.map(values))


  override def toArray()
                      (implicit ink: Integral[K],
                       cv: ClassTag[V], nuv: Numeric[V]): Array[V] = values


  override def totalIter()
                        (implicit ink: Integral[K],
                         nuv: Numeric[V]): Iterator[(K, V)] =
    values.iterator
      .zipWithIndex.map { case (v, i) => (ink.fromInt(i), v) }


  override def activeIter()
                         (implicit ink: Integral[K],
                          nuv: Numeric[V]): Iterator[(K, V)] =
    totalIter.filter(t => t._2 != nuv.zero)


  def reverseActiveIter()
                       (implicit ink: Integral[K],
                        nuv: Numeric[V]): Iterator[(K, V)] =
    values.reverseIterator
      .zipWithIndex
      .map { case (v, i) => (ink.fromInt(len - 1 - i), v) }
      .filter(t => t._2 != nuv.zero)

  override def nnz()
                  (implicit nuv: Numeric[V]): Int =
    values.count(_ != nuv.zero)

  override def toDense()
                      (implicit ink: Integral[K],
                       cv: ClassTag[V], nuv: Numeric[V]): KVVector[K, V] = this

  override def toSparse()
                       (implicit ck: ClassTag[K], ink: Integral[K],
                        cv: ClassTag[V], nuv: Numeric[V]): KVVector[K, V] = {
    val indexBuilder = mutable.ArrayBuilder.make[K]
    val valueBuilder = mutable.ArrayBuilder.make[V]
    activeIter.foreach { case (i, v) =>
      indexBuilder += i
      valueBuilder += v
    }
    KVVector.sparse[K, V](len, indexBuilder.result(), valueBuilder.result())
  }

  def isDense: Boolean = true

  override def toString: String =
    s"${values.mkString("[", ",", "]")}"
}


class SparseKVVector[@spec(Byte, Short, Int) K, @spec(Byte, Short, Int, Long, Float, Double) V](val len: Int,
                                                                                                val indices: Array[K],
                                                                                                val values: Array[V]) extends KVVector[K, V] {

  require(indices.length == values.length)
  require(len >= 0)

  override def negate()
                     (implicit cv: ClassTag[V], nuv: Numeric[V]): KVVector[K, V] = {
    KVVector.sparse[K, V](len, indices, values.map(nuv.negate))
  }

  override def apply(index: Int)
                    (implicit ink: Integral[K], nek: NumericExt[K],
                     nuv: Numeric[V]): V = {
    val j = nek.search(indices, ink.fromInt(index))
    if (j >= 0) {
      values(j)
    } else {
      nuv.zero
    }
  }

  override def plus(index: K, value: V)
                   (implicit ck: ClassTag[K], ink: Integral[K], nek: NumericExt[K],
                    cv: ClassTag[V], nuv: Numeric[V]): KVVector[K, V] = {
    require(ink.gteq(index, ink.zero))

    import nuv._

    val newLen = math.max(ink.toInt(index) + 1, len)

    if (value == zero) {
      if (len == newLen) {
        this
      } else {
        KVVector.sparse[K, V](newLen, indices, values)
      }

    } else {
      val j = nek.search(indices, index)
      if (j >= 0) {
        values(j) += value
        this

      } else {
        val left = -j - 1
        val right = indices.length - left
        val newIndices = indices.take(left) ++ Array(index) ++ indices.takeRight(right)
        val newValues = values.take(left) ++ Array(value) ++ values.takeRight(right)
        KVVector.sparse[K, V](newLen, newIndices, newValues)
      }
    }
  }

  override def slice(sorted: Array[Int])
                    (implicit ck: ClassTag[K], ink: Integral[K],
                     cv: ClassTag[V], nuv: Numeric[V]): KVVector[K, V] = {
    val indexBuilder = mutable.ArrayBuilder.make[K]
    val valueBuilder = mutable.ArrayBuilder.make[V]

    var i = 0
    var j = 0
    while (i < sorted.length && j < indices.length) {
      val k = ink.toInt(indices(j))
      if (sorted(i) == k) {
        indexBuilder += ink.fromInt(i)
        valueBuilder += values(j)
        i += 1
        j += 1
      } else if (sorted(i) > k) {
        j += 1
      } else {
        i += 1
      }
    }

    KVVector.sparse[K, V](sorted.length, indexBuilder.result(), valueBuilder.result())
  }

  override def toArray()
                      (implicit ink: Integral[K],
                       cv: ClassTag[V], nuv: Numeric[V]): Array[V] = {
    totalIter.map(_._2).toArray
  }

  override def totalIter()
                        (implicit ink: Integral[K],
                         nuv: Numeric[V]): Iterator[(K, V)] = new Iterator[(K, V)]() {
    private var i = 0
    private var j = 0

    override def hasNext: Boolean = i < len

    override def next: (K, V) = {
      val v = if (j == indices.length) {
        nuv.zero
      } else {
        val k = ink.toInt(indices(j))
        if (i == k) {
          j += 1
          values(j - 1)
        } else {
          nuv.zero
        }
      }
      i += 1
      (ink.fromInt(i - 1), v)
    }
  }

  override def activeIter()
                         (implicit ink: Integral[K],
                          nuv: Numeric[V]): Iterator[(K, V)] =
    indices.iterator.zip(values.iterator)
      .filter(t => t._2 != nuv.zero)

  def reverseActiveIter()
                       (implicit ink: Integral[K],
                        nuv: Numeric[V]): Iterator[(K, V)] =
    indices.reverseIterator.zip(values.reverseIterator)
      .filter(t => t._2 != nuv.zero)

  override def nnz()
                  (implicit nuv: Numeric[V]): Int =
    values.count(_ != nuv.zero)

  override def toDense()
                      (implicit ink: Integral[K],
                       cv: ClassTag[V], nuv: Numeric[V]): KVVector[K, V] =
    KVVector.dense[K, V](toArray)

  override def toSparse()
                       (implicit ck: ClassTag[K], ink: Integral[K],
                        cv: ClassTag[V], nuv: Numeric[V]): KVVector[K, V] = {
    if (indices.length == nnz) {
      this

    } else {
      val indexBuilder = mutable.ArrayBuilder.make[K]
      val valueBuilder = mutable.ArrayBuilder.make[V]
      activeIter.foreach { case (i, v) =>
        indexBuilder += i
        valueBuilder += v
      }
      KVVector.sparse[K, V](len, indexBuilder.result(), valueBuilder.result())
    }
  }

  def isDense: Boolean = false

  override def toString: String = {
    s"$len, ${indices.zip(values).map { case (k, v) => s"$k->$v" }.mkString("[", ",", "]")}"
  }
}


class KVMatrix[@spec(Byte, Short, Int) K, @spec(Byte, Short, Int) V](
                                                                      val indices: Array[K],
                                                                      val values: Array[V],
                                                                      val steps: Array[Int],
                                                                      val vecLen: Int) extends Serializable {
  require(vecLen >= 0)

  if (steps.nonEmpty) {
    require(steps.length == size)
  } else if (vecLen > 0) {
    require(values.length % vecLen == 0)
  }


  def size: Int = {
    if (steps.nonEmpty) {
      steps.length
    } else if (vecLen > 0) {
      values.length / vecLen
    } else {
      0
    }
  }


  def getStep(i: Int): Int = {
    if (steps.nonEmpty) {
      steps(i)
    } else {
      vecLen
    }
  }

  def iterator()
              (implicit ck: ClassTag[K], nek: NumericExt[K],
               cv: ClassTag[V], nev: NumericExt[V]): Iterator[KVVector[K, V]] = {

    val size_ = size

    new Iterator[KVVector[K, V]]() {
      var i = 0
      var indexIdx = 0
      var valueIdx = 0

      val indexBuilder = mutable.ArrayBuilder.make[K]
      val valueBuilder = mutable.ArrayBuilder.make[V]

      val emptyVec = KVVector.sparse[K, V](vecLen, nek.emptyArray, nev.emptyArray)

      override def hasNext: Boolean = i < size_

      override def next(): KVVector[K, V] = {
        val step = getStep(i)

        if (step > 0) {
          valueBuilder.clear()

          var j = 0
          while (j < step) {
            valueBuilder += values(valueIdx + j)
            j += 1
          }

          i += 1
          valueIdx += step

          KVVector.dense[K, V](valueBuilder.result())

        } else if (step < 0) {
          indexBuilder.clear()
          valueBuilder.clear()

          var j = 0
          while (j < -step) {
            indexBuilder += indices(indexIdx + j)
            valueBuilder += values(valueIdx + j)
            j += 1
          }

          i += 1
          indexIdx -= step
          valueIdx -= step

          KVVector.sparse[K, V](vecLen, indexBuilder.result(), valueBuilder.result())

        } else {

          i += 1
          emptyVec
        }
      }
    }
  }
}

object KVMatrix extends Serializable {

  def build[K, V](iterator: Iterator[KVVector[K, V]])
                 (implicit ck: ClassTag[K], cv: ClassTag[V]): KVMatrix[K, V] = {
    val indexBuilder = mutable.ArrayBuilder.make[K]
    val valueBuilder = mutable.ArrayBuilder.make[V]
    val stepBuilder = mutable.ArrayBuilder.make[Int]

    var allDense = true
    var len = -1

    iterator.foreach { vec =>
      require(vec.len > 0)
      if (len < 0) {
        len = vec.len
      }
      require(len == vec.len)

      vec match {
        case dv: DenseKVVector[K, V] =>
          valueBuilder ++= dv.values
          stepBuilder += dv.values.length

        case sv: SparseKVVector[K, V] =>
          allDense = false
          indexBuilder ++= sv.indices
          valueBuilder ++= sv.values
          stepBuilder += -sv.values.length
      }
    }

    val steps = if (allDense) {
      Array.emptyIntArray
    } else {
      stepBuilder.result()
    }

    new KVMatrix[K, V](indexBuilder.result(), valueBuilder.result(), steps, len)
  }
}


class ArrayBlock[@spec(Byte, Short, Int, Long, Float, Double) V](val values: Array[V],
                                                                 val steps: Array[Int],
                                                                 val defaultStep: Int) extends Serializable {
  if (steps.nonEmpty) {
    require(defaultStep == 0)
  } else if (defaultStep > 0) {
    require(values.length % defaultStep == 0)
  }

  def isEmpty: Boolean = size == 0

  def size: Int = {
    if (steps.nonEmpty) {
      steps.length
    } else if (defaultStep > 0) {
      values.length / defaultStep
    } else {
      0
    }
  }

  def iterator()
              (implicit cv: ClassTag[V]): Iterator[Array[V]] = {
    if (steps.nonEmpty) {

      new Iterator[Array[V]]() {
        var i = 0
        var offset = 0

        val builder = mutable.ArrayBuilder.make[V]

        override def hasNext: Boolean = i < steps.length

        override def next(): Array[V] = {
          builder.clear()

          val step = steps(i)

          var j = 0
          while (j < step) {
            builder += values(offset + j)
            j += 1
          }

          i += 1
          offset += step
          builder.result()
        }
      }

    } else if (defaultStep > 0) {
      values.grouped(defaultStep)

    } else {
      Iterator.empty
    }
  }
}


object ArrayBlock extends Serializable {

  def empty[V]()
              (implicit cv: ClassTag[V], nev: NumericExt[V]): ArrayBlock[V] = {
    new ArrayBlock[V](nev.emptyArray, Array.emptyIntArray, 0)
  }

  def build[V](iterator: Iterator[Array[V]])
              (implicit cv: ClassTag[V]): ArrayBlock[V] = {
    val valueBuilder = mutable.ArrayBuilder.make[V]
    val stepBuilder = mutable.ArrayBuilder.make[Int]

    iterator.foreach { array =>
      valueBuilder ++= array
      stepBuilder += array.length
    }

    val values = valueBuilder.result()
    val steps = stepBuilder.result()

    if (steps.distinct.length == 1 && steps.head > 0) {
      new ArrayBlock[V](values, Array.emptyIntArray, steps.head)
    } else {
      new ArrayBlock[V](values, steps, 0)
    }
  }

  def fill[V](array: Array[V], n: Int)
             (implicit cv: ClassTag[V]): ArrayBlock[V] = {
    val iter = Iterator.range(0, n).map(_ => array)
    build[V](iter)
  }
}


private trait NumericExt[K] extends Serializable {

  def emptyArray: Array[K]

  def fromFloat(value: Float): K

  def fromDouble(value: Double): K

  def fromDouble(array: Array[Double]): Array[K]

  def toDouble(array: Array[K]): Array[Double]

  def sqrt(value: K): K

  def search(array: Array[K], value: K): Int

  def size: Int
}

private object ByteNumericExt extends NumericExt[Byte] {

  override def emptyArray: Array[Byte] = Array.emptyByteArray

  override def fromFloat(value: Float): Byte = value.toByte

  override def fromDouble(value: Double): Byte = value.toByte

  override def fromDouble(array: Array[Double]): Array[Byte] = array.map(_.toByte)

  override def toDouble(array: Array[Byte]): Array[Double] = array.map(_.toDouble)

  override def sqrt(value: Byte): Byte = math.sqrt(value).toByte

  override def search(array: Array[Byte], value: Byte): Int = ju.Arrays.binarySearch(array, value)

  override def size: Int = 1
}

private object ShortNumericExt extends NumericExt[Short] {

  override def emptyArray: Array[Short] = Array.emptyShortArray

  override def fromFloat(value: Float): Short = value.toShort

  override def fromDouble(value: Double): Short = value.toShort

  override def fromDouble(array: Array[Double]): Array[Short] = array.map(_.toShort)

  override def toDouble(array: Array[Short]): Array[Double] = array.map(_.toDouble)

  override def sqrt(value: Short): Short = math.sqrt(value).toByte

  override def search(array: Array[Short], value: Short): Int = ju.Arrays.binarySearch(array, value)

  override def size: Int = 2
}

private object IntNumericExt extends NumericExt[Int] {

  override def emptyArray: Array[Int] = Array.emptyIntArray

  override def fromFloat(value: Float): Int = value.toInt

  override def fromDouble(value: Double): Int = value.toInt

  override def fromDouble(array: Array[Double]): Array[Int] = array.map(_.toInt)

  override def toDouble(array: Array[Int]): Array[Double] = array.map(_.toDouble)

  override def sqrt(value: Int): Int = math.sqrt(value).toInt

  override def search(array: Array[Int], value: Int): Int = ju.Arrays.binarySearch(array, value)

  override def size: Int = 4
}

private object LongNumericExt extends NumericExt[Long] {

  override def emptyArray: Array[Long] = Array.emptyLongArray

  override def fromFloat(value: Float): Long = value.toLong

  override def fromDouble(value: Double): Long = value.toLong

  override def fromDouble(array: Array[Double]): Array[Long] = array.map(_.toLong)

  override def toDouble(array: Array[Long]): Array[Double] = array.map(_.toDouble)

  override def sqrt(value: Long): Long = math.sqrt(value).toLong

  override def search(array: Array[Long], value: Long): Int = ju.Arrays.binarySearch(array, value)

  override def size: Int = 8
}

private object FloatNumericExt extends NumericExt[Float] {

  override def emptyArray: Array[Float] = Array.emptyFloatArray

  override def fromFloat(value: Float): Float = value

  override def fromDouble(value: Double): Float = value.toFloat

  override def fromDouble(array: Array[Double]): Array[Float] = array.map(_.toFloat)

  override def toDouble(array: Array[Float]): Array[Double] = array.map(_.toDouble)

  override def sqrt(value: Float): Float = math.sqrt(value).toFloat

  override def search(array: Array[Float], value: Float): Int = ju.Arrays.binarySearch(array, value)

  override def size: Int = 4
}

private object DoubleNumericExt extends NumericExt[Double] {

  override def emptyArray: Array[Double] = Array.emptyDoubleArray

  override def fromFloat(value: Float): Double = value.toDouble

  override def fromDouble(value: Double): Double = value

  override def fromDouble(array: Array[Double]): Array[Double] = array

  override def toDouble(array: Array[Double]): Array[Double] = array

  override def sqrt(value: Double): Double = math.sqrt(value)

  override def search(array: Array[Double], value: Double): Int = ju.Arrays.binarySearch(array, value)

  override def size: Int = 8
}


private[gbm] object NumericExt {

  implicit final val byteNumericExt: NumericExt[Byte] = ByteNumericExt

  implicit final val shortNumericExt: NumericExt[Short] = ShortNumericExt

  implicit final val intNumericExt: NumericExt[Int] = IntNumericExt

  implicit final val longNumericExt: NumericExt[Long] = LongNumericExt

  implicit final val floatNumericExt: NumericExt[Float] = FloatNumericExt

  implicit final val doubleNumericExt: NumericExt[Double] = DoubleNumericExt
}


private[gbm] object ArrayConverters {

  implicit def floatArrayToDouble(array: Array[Float]): Array[Double] = array.map(_.toDouble)

  implicit def doubleArrayToFloat(array: Array[Double]): Array[Float] = array.map(_.toFloat)
}
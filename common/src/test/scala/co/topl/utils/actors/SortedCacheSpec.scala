package co.topl.utils.actors

import org.scalatest.OptionValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class SortedCacheSpec extends AnyFlatSpec with Matchers with OptionValues {

  behavior of "PoppableItemsCache.Impl"

  private val popLimit = 50
  private val sizeLimit = 50

  it should "pop None when empty" in {
    val empty = SortedCache.Impl[TestItem](Nil, popLimit, sizeLimit, _ => ())
    val (nextCache, popped) =
      empty.pop(_ => true)

    popped shouldBe None
    nextCache shouldBe empty
  }

  it should "insert and pop a value" in {
    val empty = SortedCache.Impl[TestItem](Nil, popLimit, sizeLimit, _ => ())
    val withItem =
      empty.append(List(TestItem("1a", 1)))
    val (nextCache, popped) =
      withItem.pop(_ => true)
    nextCache shouldBe empty
    popped shouldBe Some(TestItem("1a", 1))
  }

  it should "evict a non-viable candidate after max attempts" in {
    val popLimit = 3
    val empty = SortedCache.Impl[TestItem](Nil, sizeLimit, popLimit, _ => ())
    val withItem =
      empty.append(List(TestItem("1a", 1)))

    val implAfter2Attempts =
      (0 until (popLimit - 1)).foldLeft(withItem) { (impl, _) =>
        val (next, popped) =
          impl.pop(_ => false)
        popped shouldBe None
        next should not equal empty
        next
      }

    val (next, popped) =
      implAfter2Attempts.pop(_ => false)
    popped shouldBe None
    next shouldBe empty
  }

  it should "pop a sorted candidate if it is viable" in {
    val empty = SortedCache.Impl[TestItem](Nil, popLimit, sizeLimit, _ => ())
    val withItems =
      empty.append(List(TestItem("2a", 2), TestItem("1a", 1)))

    val (withoutItem1a, popped1a) =
      withItems.pop(_ => true)

    popped1a shouldBe Some(TestItem("1a", 1))

    val (withoutItem2a, popped2a) =
      withoutItem1a.pop(_ => true)

    popped2a shouldBe Some(TestItem("2a", 2))

    withoutItem2a shouldBe empty
  }

  it should "not pop a sorted candidate if it is not viable" in {
    val viableCandidates = Set("2a")
    val empty = SortedCache.Impl[TestItem](Nil, popLimit, sizeLimit, _ => ())
    val withItems =
      empty.append(List(TestItem("2a", 2), TestItem("1a", 1)))

    val (withoutItem2a, popped2a) =
      withItems.pop(item => viableCandidates.contains(item.id))

    popped2a shouldBe Some(TestItem("2a", 2))

    withoutItem2a.pop(item => viableCandidates.contains(item.id))._2 shouldBe None

  }

  it should "evict entries if the cache grows too large" in {

    var evicted: List[TestItem] = Nil

    val empty = SortedCache.Impl[TestItem](Nil, popLimit, sizeLimit, evicted :+= _)
    val withItems =
      empty.append(List.tabulate(sizeLimit + 10)(idx => TestItem(s"${idx}a", idx)))

    val withSizeLimitPopped =
      (0 until sizeLimit).foldLeft(withItems) { case (impl, idx) =>
        val (nextImpl, popped) =
          impl.pop(_ => true)
        popped.value shouldBe TestItem(s"${idx}a", idx)
        nextImpl
      }

    withSizeLimitPopped shouldBe empty

    withSizeLimitPopped.pop(_ => true)._2 shouldBe None

    evicted shouldBe (sizeLimit until (sizeLimit + 10)).toList.map(idx => TestItem(s"${idx}a", idx))
  }

}

private case class TestItem(id: String, height: Int)

private object TestItem {
  implicit val ordering: Ordering[TestItem] = (a, b) => a.height.compareTo(b.height)
}

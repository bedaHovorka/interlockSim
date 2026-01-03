package jDisco;

import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Basic tests for Head and Link classes (queue/list management)
 */
public class QueueTest {

	/**
	 * Test basic queue operations
	 */
	@Test
	public void testBasicQueueOperations() {
		Head queue = new Head();

		// Initially empty
		assertTrue("Queue should be empty", queue.empty());
		assertNull("First element should be null", queue.first());

		// Add links
		Link link1 = new Link();
		Link link2 = new Link();
		Link link3 = new Link();

		link1.follow(queue);  // Add after head (first position)
		assertFalse("Queue should not be empty", queue.empty());
		assertEquals("First should be link1", link1, queue.first());

		link2.follow(link1);  // Add after link1
		link3.follow(link2);  // Add after link2

		// Check order
		assertEquals(link1, queue.first());
		assertEquals(link2, link1.suc());
		assertEquals(link3, link2.suc());
	}

	/**
	 * Test link removal
	 */
	@Test
	public void testLinkRemoval() {
		Head queue = new Head();
		Link link1 = new Link();
		Link link2 = new Link();
		Link link3 = new Link();

		link1.follow(queue);
		link2.follow(link1);
		link3.follow(link2);

		// Remove middle link
		link2.out();

		// Check links
		assertEquals(link1, queue.first());
		assertEquals(link3, link1.suc());
		// suc() returns Link only, use SUC field for full circular check
		assertEquals(queue, link3.SUC);  // Circular - back to head
	}

	/**
	 * Test precede operation
	 */
	@Test
	public void testPrecedeOperation() {
		Head queue = new Head();
		Link link1 = new Link();
		Link link2 = new Link();

		link1.follow(queue);  // Add first
		link2.precede(queue); // Add last (before head)

		// Order should be: link1, link2
		assertEquals(link1, queue.first());
		assertEquals(link2, queue.last());
		assertEquals(link2, link1.suc());
	}

	/**
	 * Test circular list property
	 */
	@Test
	public void testCircularProperty() {
		Head queue = new Head();
		Link link1 = new Link();
		Link link2 = new Link();

		link1.follow(queue);
		link2.follow(link1);

		// Navigate full circle using suc() for Links
		assertEquals(link1, queue.suc());
		assertEquals(link2, queue.suc().suc());
		// suc() returns null for Head, use SUC field to check circular property
		assertEquals(queue, link2.SUC);  // Back to head
	}
}

package cz.vutbr.fit.interlockSim.xml

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import cz.vutbr.fit.interlockSim.context.EditingContext
import io.mockk.mockk
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Holder with one constructor taking every primitive type that
 * [XMLContextFactory.createNew] maps from the boxed wrapper classes.
 *
 * Top-level (not nested in the test class) so reflection finds it as a plain
 * class with no enclosing-instance requirement.
 */
class PrimitiveConstructorHolder(
	val booleanValue: Boolean,
	val intValue: Int,
	val doubleValue: Double,
	val floatValue: Float,
	val longValue: Long,
	val shortValue: Short,
	val byteValue: Byte,
	val charValue: Char
)

/**
 * Tests for the argument-type mapping in [XMLContextFactory.createNew].
 *
 * `createNew` looks up the constructor through `Class.getConstructor`, which
 * matches only primitive parameter types for boxed argument values. The
 * wrapper-to-primitive `when` in `createNew` exists to make that lookup work.
 * These tests drive all eight mapping branches in one call by passing every
 * boxed type at once.
 */
@DisplayName("XMLContextFactory.createNew primitive argument mapping")
class XMLContextFactoryCreateNewTest {
	private val factory = XMLContextFactory()

	@Test
	fun `createNew resolves a constructor taking all primitive types from boxed arguments`() {
		// Arrange — the EditingContext parameter is not used by createNew's body.
		val context = mockk<EditingContext>()

		// Act — every argument arrives as a boxed wrapper.
		val created =
			factory.createNew(
				context,
				PrimitiveConstructorHolder::class.java,
				true,
				1,
				2.5,
				3.5f,
				4L,
				5.toShort(),
				6.toByte(),
				'x'
			) as PrimitiveConstructorHolder

		// Assert — the constructor ran with the exact argument values.
		assertThat(created.booleanValue).isTrue()
		assertThat(created.intValue).isEqualTo(1)
		assertThat(created.doubleValue).isEqualTo(2.5)
		assertThat(created.floatValue).isEqualTo(3.5f)
		assertThat(created.longValue).isEqualTo(4L)
		assertThat(created.shortValue).isEqualTo(5)
		assertThat(created.byteValue).isEqualTo(6)
		assertThat(created.charValue).isEqualTo('x')
	}
}

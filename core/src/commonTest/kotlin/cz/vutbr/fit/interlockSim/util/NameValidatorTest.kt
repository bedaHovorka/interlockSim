package cz.vutbr.fit.interlockSim.util

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.hasSize
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isNotEmpty
import assertk.assertions.isTrue
import kotlin.test.Test

/**
 * Tests for [NameValidator], which enforces the railway element name pattern from data.xsd:
 * non-blank, at most 50 characters, and matching `[a-zA-Z0-9_-]+`.
 */
class NameValidatorTest {
	class ValidateAccepts {
		@Test
		fun `validate accepts a simple lowercase name`() {
			val result = NameValidator.validate("track")
			assertThat(result.isValid).isTrue()
			assertThat(result.errors).isEmpty()
		}

		@Test
		fun `validate accepts letters digits underscore and hyphen together`() {
			val result = NameValidator.validate("Rail_Switch-01")
			assertThat(result.isValid).isTrue()
			assertThat(result.errors).isEmpty()
		}

		@Test
		fun `validate accepts a name of exactly 50 characters`() {
			val name = "a".repeat(50)
			val result = NameValidator.validate(name)
			assertThat(result.isValid).isTrue()
			assertThat(result.errors).isEmpty()
		}

		@Test
		fun `validate accepts a single character name`() {
			val result = NameValidator.validate("a")
			assertThat(result.isValid).isTrue()
			assertThat(result.errors).isEmpty()
		}
	}

	class ValidateRejects {
		@Test
		fun `validate rejects an empty name with exactly one error`() {
			val result = NameValidator.validate("")
			assertThat(result.isValid).isFalse()
			assertThat(result.errors).hasSize(1)
			assertThat(result.errors[0].message).isEqualTo("Name cannot be empty")
		}

		@Test
		fun `validate rejects a whitespace-only name with exactly one error`() {
			// This is the case that proves the pattern check is skipped for blank input:
			// a blank name must NOT also be reported as containing invalid characters.
			val result = NameValidator.validate("   ")
			assertThat(result.isValid).isFalse()
			assertThat(result.errors).hasSize(1)
			assertThat(result.errors[0].message).isEqualTo("Name cannot be empty")
		}

		@Test
		fun `validate rejects a 51-character name as too long`() {
			val name = "a".repeat(51)
			val result = NameValidator.validate(name)
			assertThat(result.isValid).isFalse()
			assertThat(result.errors).hasSize(1)
			assertThat(result.errors[0].message).isEqualTo("Name too long (max 50 characters, got 51)")
		}

		@Test
		fun `validate rejects a name with a space`() {
			val result = NameValidator.validate("has space")
			assertThat(result.isValid).isFalse()
			assertThat(result.errors).hasSize(1)
			assertThat(result.errors[0].message).isEqualTo("Name contains invalid characters")
		}

		@Test
		fun `validate rejects a name with a non-ASCII letter`() {
			val result = NameValidator.validate("vyhybná")
			assertThat(result.isValid).isFalse()
			assertThat(result.errors).hasSize(1)
			assertThat(result.errors[0].message).isEqualTo("Name contains invalid characters")
		}

		@Test
		fun `validate rejects a name with punctuation`() {
			val result = NameValidator.validate("a.b")
			assertThat(result.isValid).isFalse()
			assertThat(result.errors).hasSize(1)
			assertThat(result.errors[0].message).isEqualTo("Name contains invalid characters")
		}

		@Test
		fun `validate rejects a too-long name with invalid characters as two errors`() {
			val name = "a".repeat(50) + "!"
			val result = NameValidator.validate(name)
			assertThat(result.isValid).isFalse()
			assertThat(result.errors).hasSize(2)
			val messages = result.errors.map { it.message }
			assertThat(messages).contains("Name too long (max 50 characters, got 51)")
			assertThat(messages).contains("Name contains invalid characters")
		}
	}

	class ErrorShape {
		@Test
		fun `validate error carries category severity and a non-blank explanation`() {
			val result = NameValidator.validate("")
			val error = result.errors[0]
			assertThat(error.category).isEqualTo(ErrorCategory.CONFIGURATION)
			assertThat(error.severity).isEqualTo(ValidationSeverity.ERROR)
			assertThat(error.explanation).isNotEmpty()
		}
	}

	class IsValid {
		@Test
		fun `isValid returns true for a valid name`() {
			assertThat(NameValidator.isValid("track")).isTrue()
		}

		@Test
		fun `isValid returns false for an invalid name`() {
			assertThat(NameValidator.isValid("has space")).isFalse()
		}

		@Test
		fun `isValid agrees with validate isValid for a range of names`() {
			val names = listOf("track", "", "has space", "Rail_Switch-01", "a".repeat(51))
			for (name in names) {
				assertThat(NameValidator.isValid(name)).isEqualTo(NameValidator.validate(name).isValid)
			}
		}
	}
}

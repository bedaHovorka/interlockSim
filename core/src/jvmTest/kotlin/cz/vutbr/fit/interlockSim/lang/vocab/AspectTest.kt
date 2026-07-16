package cz.vutbr.fit.interlockSim.lang.vocab

import assertk.assertThat
import assertk.assertions.isEqualTo
import cz.vutbr.fit.interlockSim.lang.LangSerialization
import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Serialisation round-trip tests for the [Aspect] sealed hierarchy (SP3.2, Issue #570).
 *
 * Verifies that:
 *  - every concrete subtype serialises to the correct JSON string,
 *  - every JSON string deserialises back to the correct Kotlin object,
 *  - parameterised aspects carry their values through the round-trip.
 *
 * Every subtype has an exact-JSON `*SerialName` test pinning the stable `@SerialName`
 * discriminator, so a silent rename of a discriminator fails this suite rather than only
 * passing round-trip.
 */
class AspectTest {
	private val json = LangSerialization.json

	@Nested
	inner class SingletonAspects {
		@Test
		fun stujRoundTrip() {
			val aspect: Aspect = Aspect.Stuj
			val encoded = json.encodeToString(aspect)
			assertThat(json.decodeFromString<Aspect>(encoded)).isEqualTo(Aspect.Stuj)
		}

		@Test
		fun stujSerialName() {
			val encoded = json.encodeToString<Aspect>(Aspect.Stuj)
			assertThat(encoded).isEqualTo("""{"type":"stuj"}""")
		}

		@Test
		fun volnoRoundTrip() {
			val aspect: Aspect = Aspect.Volno
			val encoded = json.encodeToString(aspect)
			assertThat(json.decodeFromString<Aspect>(encoded)).isEqualTo(Aspect.Volno)
		}

		@Test
		fun volnoSerialName() {
			val encoded = json.encodeToString<Aspect>(Aspect.Volno)
			assertThat(encoded).isEqualTo("""{"type":"volno"}""")
		}

		@Test
		fun vystrahaRoundTrip() {
			val aspect: Aspect = Aspect.Vystraha
			val encoded = json.encodeToString(aspect)
			assertThat(json.decodeFromString<Aspect>(encoded)).isEqualTo(Aspect.Vystraha)
		}

		@Test
		fun vystrahaSerialName() {
			val encoded = json.encodeToString<Aspect>(Aspect.Vystraha)
			assertThat(encoded).isEqualTo("""{"type":"vystraha"}""")
		}

		@Test
		fun privolavaciNavestRoundTrip() {
			val aspect: Aspect = Aspect.PrivolavaciNavest
			val encoded = json.encodeToString(aspect)
			assertThat(json.decodeFromString<Aspect>(encoded)).isEqualTo(Aspect.PrivolavaciNavest)
		}

		@Test
		fun privolavaciNavestSerialName() {
			val encoded = json.encodeToString<Aspect>(Aspect.PrivolavaciNavest)
			assertThat(encoded).isEqualTo("""{"type":"privolavaci_navest"}""")
		}

		@Test
		fun posunDovolenRoundTrip() {
			val aspect: Aspect = Aspect.PosunDovolen
			val encoded = json.encodeToString(aspect)
			assertThat(json.decodeFromString<Aspect>(encoded)).isEqualTo(Aspect.PosunDovolen)
		}

		@Test
		fun posunDovolenSerialName() {
			val encoded = json.encodeToString<Aspect>(Aspect.PosunDovolen)
			assertThat(encoded).isEqualTo("""{"type":"posun_dovolen"}""")
		}

		@Test
		fun posunZakazanRoundTrip() {
			val aspect: Aspect = Aspect.PosunZakazan
			val encoded = json.encodeToString(aspect)
			assertThat(json.decodeFromString<Aspect>(encoded)).isEqualTo(Aspect.PosunZakazan)
		}

		@Test
		fun posunZakazanSerialName() {
			val encoded = json.encodeToString<Aspect>(Aspect.PosunZakazan)
			assertThat(encoded).isEqualTo("""{"type":"posun_zakazan"}""")
		}
	}

	@Nested
	inner class ParameterisedAspects {
		@Test
		fun rychlostRoundTrip() {
			val aspect: Aspect = Aspect.Rychlost(40)
			val encoded = json.encodeToString(aspect)
			val decoded = json.decodeFromString<Aspect>(encoded)
			assertThat(decoded).isEqualTo(Aspect.Rychlost(40))
		}

		@Test
		fun rychlostSerialName() {
			val encoded = json.encodeToString<Aspect>(Aspect.Rychlost(60))
			assertThat(encoded).isEqualTo("""{"type":"rychlost","kmh":60}""")
		}

		@Test
		fun ocekavejteRoundTrip() {
			val aspect: Aspect = Aspect.Ocekavejte(80)
			val encoded = json.encodeToString(aspect)
			val decoded = json.decodeFromString<Aspect>(encoded)
			assertThat(decoded).isEqualTo(Aspect.Ocekavejte(80))
		}

		@Test
		fun ocekavejteSerialName() {
			val encoded = json.encodeToString<Aspect>(Aspect.Ocekavejte(100))
			assertThat(encoded).isEqualTo("""{"type":"ocekavejte","kmh":100}""")
		}

		@Test
		fun rychlostRejectsNonPositiveSpeed() {
			org.junit.jupiter.api
				.assertThrows<IllegalArgumentException> { Aspect.Rychlost(0) }
			org.junit.jupiter.api
				.assertThrows<IllegalArgumentException> { Aspect.Rychlost(-5) }
		}

		@Test
		fun ocekavejteRejectsNonPositiveSpeed() {
			org.junit.jupiter.api
				.assertThrows<IllegalArgumentException> { Aspect.Ocekavejte(0) }
			org.junit.jupiter.api
				.assertThrows<IllegalArgumentException> { Aspect.Ocekavejte(-5) }
		}
	}

	@Test
	fun exhaustiveWhenNoBranchNeeded() {
		// Compile-time check that when(aspect) is exhaustive without an else branch.
		val aspects: List<Aspect> =
			listOf(
				Aspect.Stuj,
				Aspect.Volno,
				Aspect.Vystraha,
				Aspect.Rychlost(40),
				Aspect.Ocekavejte(60),
				Aspect.PrivolavaciNavest,
				Aspect.PosunDovolen,
				Aspect.PosunZakazan
			)
		val labels: List<String> =
			aspects.map { aspect ->
				when (aspect) {
					is Aspect.Stuj -> "stuj"
					is Aspect.Volno -> "volno"
					is Aspect.Vystraha -> "vystraha"
					is Aspect.Rychlost -> "rychlost:${aspect.kmh}"
					is Aspect.Ocekavejte -> "ocekavejte:${aspect.kmh}"
					is Aspect.PrivolavaciNavest -> "privolavaci_navest"
					is Aspect.PosunDovolen -> "posun_dovolen"
					is Aspect.PosunZakazan -> "posun_zakazan"
				}
			}
		assertThat(labels.size).isEqualTo(aspects.size)
	}

	@Test
	fun humanLabelReturnsProperCzechText() {
		assertThat(Aspect.Stuj.humanLabel()).isEqualTo("Stůj")
		assertThat(Aspect.Volno.humanLabel()).isEqualTo("Volno")
		assertThat(Aspect.Vystraha.humanLabel()).isEqualTo("Výstraha")
		assertThat(Aspect.Rychlost(40).humanLabel()).isEqualTo("Rychlost 40 km/h")
		assertThat(Aspect.Ocekavejte(60).humanLabel()).isEqualTo("Očekávejte rychlost 60 km/h")
		assertThat(Aspect.PrivolavaciNavest.humanLabel()).isEqualTo("Přivolávací návěst")
		assertThat(Aspect.PosunDovolen.humanLabel()).isEqualTo("Posun dovolen")
		assertThat(Aspect.PosunZakazan.humanLabel()).isEqualTo("Posun zakázán")
	}
}

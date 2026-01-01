/*
  File: DiscoException.java

  Originally written by Keld Helsgaun and released into the public domain.
  This may be used for any purposes whatsoever without acknowledgment.

  History:
  Date         Who                What
  20Sep2001     kh         Created public version
*/

package jDisco;

/**
 * jDisco throws this runtime exception whenever it detects an error.
 */
public class DiscoException extends RuntimeException {
	DiscoException(String message) {
		super(message);
	}
}

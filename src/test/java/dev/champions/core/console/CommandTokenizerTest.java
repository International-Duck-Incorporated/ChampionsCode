package dev.champions.core.console;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CommandTokenizerTest {
	@Test void supportsQuotesAndEscapes() {
		assertEquals(List.of("set", "RHOST", "lab server"), CommandTokenizer.tokenize("set RHOST \"lab server\""));
		assertEquals(List.of("print", "a b"), CommandTokenizer.tokenize("print a\\ b"));
	}

	@Test void rejectsUnclosedQuote() {
		assertThrows(IllegalArgumentException.class, () -> CommandTokenizer.tokenize("set RHOST 'oops"));
	}
}

package com.ultimakingdoms.naming;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class NameRulesTest {
 @Test void normalizationIgnoresAccentsCaseWhitespaceAndPunctuation(){assertEquals("lakesend",VillageNameGenerator.normalize(" LÁKE's_End! "));}
 @Test void composedAndDecomposedAccentsMatch(){assertEquals(VillageNameGenerator.normalize("Café"),VillageNameGenerator.normalize("Cafe\u0301"));}
 @Test void templateSubstitutionTreatsTokenTextLiterally(){var t=new NameTemplate("{root} End",1);assertEquals("$Town\\ End",t.generate(Map.of("root",List.of("$Town\\")),new SplittableRandom(1)));}
 @Test void unknownTokensAreRejected(){assertThrows(IllegalArgumentException.class,()->new NameTemplate("{missing}",1).validateTokens(Map.of()));}
 @Test void zeroWeightRejected(){assertThrows(IllegalArgumentException.class,()->new NameTemplate("{root}",0));}
 @Test void deterministicTokenChoice(){var t=new NameTemplate("{root} Haven",3);var tokens=Map.of("root",List.of("West","East"));assertEquals(t.generate(tokens,new SplittableRandom(44)),t.generate(tokens,new SplittableRandom(44)));}
}

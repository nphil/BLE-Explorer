package dev.nphil.blueshark.identify

/**
 * Names the ecosystem a device belongs to, best guess first.
 *
 * This is the first stage of a device project and it is deliberately free: everything it reads is
 * already in a scan record, so the operator learns what the device probably is before deciding
 * whether to connect, probe, or take a capture. A match is worth having only if the operator can
 * audit it, which is why every rule appends the bytes it used to [FamilyMatch.evidence] instead of
 * returning a bare verdict.
 *
 * Two-step by construction: [Family.detect] reads the advertisement, [Family.confirm] reads the
 * GATT database and can only *raise* an existing match to [FamilyConfidence.CERTAIN]. A family is
 * never claimed from GATT alone, because the vendor-generic attribute pairs (fff0/fff1, 1910/1911,
 * a201/a202) are shared by unrelated modules; those show up through [commandChannelMatches]
 * instead, which promises nothing about the protocol - only that something can be written and
 * something answers.
 */
object DeviceFingerprint {

    /** Best first: [FamilyConfidence], then the amount of evidence, then the id for stability. */
    private val BEST_FIRST = compareBy<FamilyMatch>(
        { it.confidence.ordinal },
        { -it.evidence.size },
        { it.familyId },
    )

    fun identify(input: FingerprintInput): List<FamilyMatch> {
        val advert = Advert(input)
        val gatt = GattView(input.gatt)
        val matches = ArrayList<FamilyMatch>(Families.all.size + 2)
        for (family in Families.all) {
            val detection = family.detect(advert) ?: continue
            val confirmation = family.confirm(gatt)
            matches += FamilyMatch(
                familyId = family.id,
                name = family.name,
                confidence = if (confirmation == null) detection.confidence else FamilyConfidence.CERTAIN,
                evidence = if (confirmation == null) detection.evidence else detection.evidence + confirmation,
                publicDriverUrl = family.driverUrl,
                codecId = family.codecId,
                commandCharacteristicHints = family.hints,
            )
        }
        matches += commandChannelMatches(gatt)
        matches.sortWith(BEST_FIRST)
        return matches
    }
}

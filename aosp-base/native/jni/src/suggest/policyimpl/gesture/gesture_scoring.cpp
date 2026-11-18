/*
 * gti.dev: see gesture_scoring.h for the rationale of this policy.
 */

#include "suggest/policyimpl/gesture/gesture_scoring.h"

#include "suggest/core/dicnode/dic_node.h"

namespace latinime {

namespace {
// Demotion applied to words whose double letter was traced as a single point.
const float GESTURE_DOUBLE_LETTER_DEMOTION_COST = 0.5f;
const float GESTURE_STRONG_DOUBLE_LETTER_DEMOTION_COST = 1.0f;
} // namespace

const GestureScoring GestureScoring::sInstance;

// gti.dev: intentionally a no-op, exactly like TypingScoring.
//
// The geometric decoder's getMostProbableString() only produces the raw greedy
// trace (e.g. "aids" / "rsr" for "teste") because the open-source AOSP lacks
// the proprietary gesture language model that aligns that trace to real words.
// Adding that raw string to SuggestionResults made the keyboard commit pure
// garbage. We only ever want real dictionary words as suggestions, so we do
// NOT emit the most probable string here. The dictionary traversal in
// GestureTraversal/GestureWeighting still supplies the real word candidates.
void GestureScoring::getMostProbableString(
        const DicTraverseSession *const traverseSession,
        const float weightOfLangModelVsSpatialModel,
        SuggestionResults *const outSuggestionResults) const {
    // no-op
}

float GestureScoring::getAdjustedWeightOfLangModelVsSpatialModel(
        DicTraverseSession *const traverseSession, DicNode *const terminals,
        const int size) const {
    // Safe neutral weight: both distances use the same compound-distance scale.
    return 1.0f;
}

float GestureScoring::getDoubleLetterDemotionDistanceCost(
        const DicNode *const terminalDicNode) const {
    switch (terminalDicNode->getDoubleLetterLevel()) {
        case A_STRONG_DOUBLE_LETTER:
            return GESTURE_STRONG_DOUBLE_LETTER_DEMOTION_COST;
        case A_DOUBLE_LETTER:
            return GESTURE_DOUBLE_LETTER_DEMOTION_COST;
        case NOT_A_DOUBLE_LETTER:
        default:
            return 0.0f;
    }
}

} // namespace latinime

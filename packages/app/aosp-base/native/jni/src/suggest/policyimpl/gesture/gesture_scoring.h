/*
 * gti.dev: gesture-aware scoring policy.
 *
 * Reuses TypingScoring for the final score conversion and implements the three
 * hooks that the typing policy leaves empty. The most probable string decoded
 * from the swipe path is emitted as an OOV gesture fallback suggestion, double
 * letters traced too quickly are demoted, and the language/spatial weight is
 * kept on the same scale as typing.
 */

#ifndef LATINIME_GESTURE_SCORING_H
#define LATINIME_GESTURE_SCORING_H

#include "defines.h"
#include "suggest/core/dictionary/error_type_utils.h"
#include "suggest/core/policy/scoring.h"
#include "suggest/core/session/dic_traverse_session.h"
#include "suggest/policyimpl/typing/typing_scoring.h"

namespace latinime {

class DicNode;
class DicTraverseSession;
class SuggestionResults;

class GestureScoring : public TypingScoring {
  public:
    static const GestureScoring *getInstance() { return &sInstance; }

    void getMostProbableString(const DicTraverseSession *const traverseSession,
            const float weightOfLangModelVsSpatialModel,
            SuggestionResults *const outSuggestionResults) const;

    float getAdjustedWeightOfLangModelVsSpatialModel(
            DicTraverseSession *const traverseSession, DicNode *const terminals,
            const int size) const;

    float getDoubleLetterDemotionDistanceCost(const DicNode *const terminalDicNode) const;

  private:
    DISALLOW_COPY_AND_ASSIGN(GestureScoring);
    static const GestureScoring sInstance;

    GestureScoring() {}
    ~GestureScoring() {}
};

} // namespace latinime
#endif // LATINIME_GESTURE_SCORING_H

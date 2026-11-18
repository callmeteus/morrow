/*
 * gti.dev: gesture-aware weighting policy.
 *
 * Inherits the mature language-model costs from TypingWeighting and replaces
 * only the spatial part. The spatial cost of a matched dictionary letter is
 * taken from the geometric probabilities that ProximityInfoState precomputes
 * for the swipe path (negative log probabilities, lower is better). The matched
 * letter also fills DicNode_InputStateG so the traversal advances over a whole
 * run of sampled points instead of exactly one point per letter.
 */

#ifndef LATINIME_GESTURE_WEIGHTING_H
#define LATINIME_GESTURE_WEIGHTING_H

#include "defines.h"
#include "suggest/core/dicnode/dic_node.h"
#include "suggest/core/layout/proximity_info_state.h"
#include "suggest/core/policy/weighting.h"
#include "suggest/core/session/dic_traverse_session.h"
#include "suggest/policyimpl/typing/typing_weighting.h"

namespace latinime {

class DicNode;
struct DicNode_InputStateG;

class GestureWeighting : public TypingWeighting {
  public:
    static const GestureWeighting *getInstance() { return &sInstance; }

  protected:
    float getMatchedCost(const DicTraverseSession *const traverseSession,
            const DicNode *const dicNode, DicNode_InputStateG *inputStateG) const;

    float getSpaceOmissionCost(const DicTraverseSession *const traverseSession,
            const DicNode *const dicNode, DicNode_InputStateG *const inputStateG) const;

    // Gesture distances are accumulated over a variable number of points, so
    // they must be normalised before they are compared across candidates.
    AK_FORCE_INLINE bool needsToNormalizeCompoundDistance() const {
        return true;
    }

  private:
    DISALLOW_COPY_AND_ASSIGN(GestureWeighting);
    static const GestureWeighting sInstance;

    GestureWeighting() {}
    ~GestureWeighting() {}
};

} // namespace latinime
#endif // LATINIME_GESTURE_WEIGHTING_H

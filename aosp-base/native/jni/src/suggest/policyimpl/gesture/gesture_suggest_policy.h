/*
 * gti.dev: open-source gesture suggest policy.
 *
 * AOSP ships only gesture_suggest_policy_factory.{h,cpp}; the real gesture
 * policy (traversal/scoring/weighting tuned for swipes) lived in the
 * proprietary libjni_latinimegoogle.so from Gboard. Without that library the
 * factory returns null, mGestureSuggest ends up with a null policy and any
 * swipe crashes the IME in Suggest::getSuggestions (SIGSEGV on this).
 *
 * This policy provides real gesture implementations built on the geometric
 * infrastructure that AOSP open source already has: search-key based
 * proximity, per-point probabilities, speed rates and double-letter levels.
 * The swipe is decoded natively with a variable number of sampled points per
 * letter, not by decoding in Java and re-feeding synthetic taps.
 */

#ifndef LATINIME_GESTURE_SUGGEST_POLICY_H
#define LATINIME_GESTURE_SUGGEST_POLICY_H

#include "defines.h"
#include "suggest/core/policy/suggest_policy.h"
#include "suggest/policyimpl/gesture/gesture_scoring.h"
#include "suggest/policyimpl/gesture/gesture_traversal.h"
#include "suggest/policyimpl/gesture/gesture_weighting.h"

namespace latinime {

class Traversal;
class Scoring;
class Weighting;

class GestureSuggestPolicy : public SuggestPolicy {
  public:
    static const GestureSuggestPolicy *getInstance() { return &sInstance; }

    GestureSuggestPolicy() {}
    virtual ~GestureSuggestPolicy() {}

    AK_FORCE_INLINE const Traversal *getTraversal() const {
        return GestureTraversal::getInstance();
    }

    AK_FORCE_INLINE const Scoring *getScoring() const {
        return GestureScoring::getInstance();
    }

    AK_FORCE_INLINE const Weighting *getWeighting() const {
        return GestureWeighting::getInstance();
    }

  private:
    DISALLOW_COPY_AND_ASSIGN(GestureSuggestPolicy);
    static const GestureSuggestPolicy sInstance;
};

} // namespace latinime
#endif // LATINIME_GESTURE_SUGGEST_POLICY_H

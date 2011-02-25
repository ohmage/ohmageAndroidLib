package edu.ucla.cens.andwellness.conditionevaluator.comparator;

import java.util.Iterator;
import java.util.List;

import edu.ucla.cens.andwellness.conditionevaluator.DataPoint;

/**
 * Comparator for the multi_choice prompt type.
 * Conditions on multi_choice can only be "==" and "!="
 * so all other condition operators return false.
 * In practice, the configuration validator doesn't even
 * allow those operators in the condition.
 * 
 * @author Mohamad Monibi
 *
 */
public class MultiChoiceDataPointComparator extends AbstractDataPointComparator {

	@Override
	boolean equals(DataPoint dataPoint, String value) {
		// Run through all the responses, if any of them are true assume the whole thing is true
        Integer valueToCompare = Integer.parseInt(value);
        Integer dataPointValue = null;
        @SuppressWarnings("unchecked")
        List<Integer> dataPointValues = (List<Integer>) dataPoint.getValue();
        Iterator<Integer> dataPointValuesIterator = dataPointValues.iterator();
        while (dataPointValuesIterator.hasNext()) {
            dataPointValue = dataPointValuesIterator.next();
            
            if (dataPointValue.compareTo(valueToCompare) == 0) {
                return true;
            }
        }
		return false;
	}

	@Override
	boolean greaterThan(DataPoint dataPoint, String value) {
		// not allowed
		// throw exception instead?
		return false;
	}

	@Override
	boolean greaterThanOrEquals(DataPoint dataPoint, String value) {
		// not allowed
		// throw exception instead?
		return false;
	}

	@Override
	boolean lessThan(DataPoint dataPoint, String value) {
		// not allowed
		// throw exception instead?
		return false;
	}

	@Override
	boolean lessThanOrEquals(DataPoint dataPoint, String value) {
		// not allowed
		// throw exception instead?
		return false;
	}

	@Override
	boolean notEquals(DataPoint dataPoint, String value) {
		// Run through all the responses, if any of them are true assume the whole thing is true
		Integer valueToCompare = Integer.parseInt(value);
        Integer dataPointValue = null;
        @SuppressWarnings("unchecked")
        List<Integer> dataPointValues = (List<Integer>) dataPoint.getValue();
        Iterator<Integer> dataPointValuesIterator = dataPointValues.iterator();
        while (dataPointValuesIterator.hasNext()) {
            dataPointValue = dataPointValuesIterator.next();
            
            if (dataPointValue.compareTo(valueToCompare) == 0) {
                return false;
            }
        }
		return true;
	}

}

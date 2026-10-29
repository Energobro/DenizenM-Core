package com.denizenscript.denizencore.utilities.data;

import com.denizenscript.denizencore.tags.TagContext;
import com.denizenscript.denizencore.utilities.CoreUtilities;
import com.denizenscript.denizencore.objects.core.ElementTag;
import com.denizenscript.denizencore.objects.core.ListTag;
import com.denizenscript.denizencore.objects.ObjectTag;
import com.denizenscript.denizencore.utilities.Deprecations;

import java.math.BigDecimal;
import java.math.RoundingMode;

public class DataAction {

    public ActionableDataProvider provider;

    public DataActionType type;

    public String key;

    /**
     * Zero = no index needed.
     * Positive number = list index (starting at 1).
     */
    public int index = 0;

    public ObjectTag inputValue = null;

    @Override
    public String toString() {
        String keyDebug = key;
        if (index != 0) {
            keyDebug += index == Integer.MAX_VALUE ? "[last]" : "[" + index + "]";
        }
        return "(" + keyDebug + ":" + type + ":" + inputValue + ")";
    }

    public ListTag autoList(String key, TagContext context) {
        ObjectTag obj = provider.getValueAt(key);
        if (obj == null) {
            return new ListTag();
        }
        else {
            return autoList(ListTag.getListFor(obj, context));
        }
    }

    public ListTag autoList(ListTag list) {
        return new ListTag(list);
    }

    public ObjectTag autoDup(ObjectTag object) {
        if (object == null) {
            return null;
        }
        if (object instanceof ListTag) {
            return autoList((ListTag) object);
        }
        return object.duplicate();
    }

    public BigDecimal autoNumber(TagContext context) {
        ObjectTag obj = provider.getValueAt(key);
        if (index != 0) {
            ListTag subList = ListTag.getListFor(obj, context);
            if (index < 0 || index > subList.size()) {
                if (index == Integer.MAX_VALUE && !subList.isEmpty()) {
                    obj = subList.getObject(subList.size() - 1);
                }
                else {
                    return BigDecimal.ZERO;
                }
            }
            else {
                obj = subList.getObject(index - 1);
            }
        }
        try {
            return autoNumber(obj);
        }
        catch (NumberFormatException ex) {
            return BigDecimal.ZERO;
        }
    }

    public BigDecimal autoNumber(ObjectTag obj) {
        if (obj == null) {
            return BigDecimal.ZERO;
        }
        return new BigDecimal(obj.toString());
    }

    public ElementTag autoNumber(BigDecimal decimal) {
        return new ElementTag(decimal);
    }

    public void autoSet(ObjectTag value, TagContext context) {
        if (index != 0) {
            ObjectTag obj = provider.getValueAt(key);
            ListTag subList = ListTag.getListFor(obj, context);
            if (index == Integer.MAX_VALUE) {
                subList.setObject(subList.isEmpty() ? 0 : (subList.size() - 1), value);
            }
            else {
                subList.setObject(index - 1, value);
            }
            value = subList;
        }
        provider.setValueAt(key, value);
    }

    public void requiresInputValue() {
        if (inputValue == null) {
            throw new DataActionException("Input value required for data action " + type + ".");
        }
    }

    private static final int LONG_ADD = 0, LONG_SUBTRACT = 1, LONG_MULTIPLY = 2;

    private static Long plainLongOf(ObjectTag object) {
        if (object == null) {
            return 0L;
        }
        return object instanceof ElementTag element ? element.asPlainLong() : null;
    }

    private boolean tryLongMath(TagContext context, ObjectTag operand, int operation) {
        if (index != 0 || getClass() != DataAction.class) {
            return false;
        }
        Long right = operand == null ? Long.valueOf(1L) : operand instanceof ElementTag element ? element.asPlainLong() : null;
        ObjectTag current = provider.getValueAt(key);
        Long left = right == null ? null : plainLongOf(current);
        if (left == null) {
            return tryFixedMath(current, operand, operation);
        }
        long result;
        try {
            result = switch (operation) {
                case LONG_ADD -> Math.addExact(left, right);
                case LONG_SUBTRACT -> Math.subtractExact(left, right);
                default -> Math.multiplyExact(left, right);
            };
        }
        catch (ArithmeticException ex) {
            return false;
        }
        ElementTag element = new ElementTag(result);
        element.setPrefix("decimal");
        provider.setValueAt(key, element);
        return true;
    }

    private boolean tryFixedMath(ObjectTag current, ObjectTag operand, int operation) {
        if (!(current instanceof ElementTag left) || !(operand instanceof ElementTag right)
                || CoreUtilities.contains(left.asString(), '%') || CoreUtilities.contains(right.asString(), '%')) {
            return false;
        }
        ElementTag result = operation == LONG_MULTIPLY ? ElementTag.fixedProduct(left, right) : ElementTag.fixedSum(left, right, operation == LONG_SUBTRACT);
        if (result == null) {
            return false;
        }
        provider.setValueAt(key, result);
        return true;
    }

    public void execute(TagContext context) {
        switch (type) {
            case INCREMENT: {
                if (tryLongMath(context, null, LONG_ADD)) {
                    break;
                }
                BigDecimal num = autoNumber(context);
                num = num.add(BigDecimal.ONE);
                autoSet(autoNumber(num), context);
                break;
            }
            case DECREMENT: {
                if (tryLongMath(context, null, LONG_SUBTRACT)) {
                    break;
                }
                BigDecimal num = autoNumber(context);
                num = num.subtract(BigDecimal.ONE);
                autoSet(autoNumber(num), context);
                break;
            }
            case ADD: {
                requiresInputValue();
                if (tryLongMath(context, inputValue, LONG_ADD)) {
                    break;
                }
                BigDecimal num = autoNumber(context);
                num = num.add(autoNumber(inputValue));
                autoSet(autoNumber(num), context);
                break;
            }
            case SUBTRACT: {
                requiresInputValue();
                if (tryLongMath(context, inputValue, LONG_SUBTRACT)) {
                    break;
                }
                BigDecimal num = autoNumber(context);
                num = num.subtract(autoNumber(inputValue));
                autoSet(autoNumber(num), context);
                break;
            }
            case MULTIPLY: {
                requiresInputValue();
                if (tryLongMath(context, inputValue, LONG_MULTIPLY)) {
                    break;
                }
                BigDecimal num = autoNumber(context);
                num = num.multiply(autoNumber(inputValue));
                autoSet(autoNumber(num), context);
                break;
            }
            case DIVIDE: {
                requiresInputValue();
                BigDecimal num = autoNumber(context);
                num = num.setScale(15, RoundingMode.HALF_UP);
                num = num.divide(autoNumber(inputValue), RoundingMode.HALF_UP);
                autoSet(autoNumber(num), context);
                break;
            }
            case INSERT: {
                requiresInputValue();
                ListTag list = autoList(key, context);
                list.addObject(inputValue);
                provider.setValueAt(key, list);
                break;
            }
            case REMOVE: {
                ListTag list = autoList(key, context);
                if (index != 0) {
                    if (index == Integer.MAX_VALUE && !list.isEmpty()) {
                        list.remove(list.size() - 1);
                    }
                    else {
                        list.remove(index - 1);
                    }
                }
                else {
                    requiresInputValue();
                    String findValue = inputValue.toString();
                    for (int i = 0; i < list.size(); i++) {
                        if (CoreUtilities.equalsIgnoreCase(list.get(i), findValue)) {
                            list.remove(i);
                            break;
                        }
                    }
                }
                provider.setValueAt(key, list);
                break;
            }
            case SPLIT: {
                requiresInputValue();
                ListTag list = autoList(key, context);
                list.addObjects(ListTag.getListFor(inputValue, context).objectForms);
                provider.setValueAt(key, list);
                break;
            }
            case SPLIT_NEW:
                Deprecations.splitNewDataAction.warn(context);
                requiresInputValue();
                provider.setValueAt(key, autoList(ListTag.getListFor(inputValue, context)));
                break;
            case SET:
                requiresInputValue();
                autoSet(autoDup(inputValue), context);
                break;
            case AUTO_SET:
                provider.setValueAt(key, new ElementTag(true));
                break;
            case CLEAR:
                provider.setValueAt(key, null);
                break;
        }
    }
}

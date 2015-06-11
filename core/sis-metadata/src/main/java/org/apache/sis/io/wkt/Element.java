/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.sis.io.wkt;

import java.util.Date;
import java.util.Map;
import java.util.List;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Locale;
import java.io.PrintWriter;
import java.text.ParsePosition;
import java.text.ParseException;
import org.apache.sis.util.Debug;
import org.apache.sis.util.ArraysExt;
import org.apache.sis.util.Exceptions;
import org.apache.sis.util.CharSequences;
import org.apache.sis.util.resources.Errors;
import org.apache.sis.internal.metadata.WKTKeywords;
import org.apache.sis.internal.util.CollectionsExt;
import org.apache.sis.internal.util.LocalizedParseException;

import static org.apache.sis.util.CharSequences.skipLeadingWhitespaces;


/**
 * An element in a <cite>Well Know Text</cite> (WKT). An {@code Element} is made of {@link String},
 * {@link Number} and other {@link Element}. For example:
 *
 * {@preformat text
 *     PrimeMeridian[“Greenwich”, 0.0, AngleUnit[“degree”, 0.017453292519943295]]]
 * }
 *
 * Each {@code Element} object can contain an arbitrary amount of other elements.
 * The result is a tree, which can be printed with {@link #print(PrintWriter, int)} for debugging purpose.
 * Elements can be pulled in a <cite>first in, first out</cite> order.
 *
 * @author  Rémi Ève (IRD)
 * @author  Martin Desruisseaux (IRD, Geomatys)
 * @since   0.6
 * @version 0.6
 * @module
 */
final class Element {
    /**
     * Kind of value expected in the element. Value 0 means "not yet determined".
     */
    private static final int NUMERIC = 1, TEMPORAL = 2;

    /**
     * Hard-coded list of elements in which to parse values as dates instead than numbers.
     * We may try to find a more generic approach in a future version.
     */
    private static final String[] TIME_KEYWORDS = {
        WKTKeywords.TimeOrigin,
        WKTKeywords.TimeExtent
    };

    /**
     * The position where this element starts in the string to be parsed.
     */
    final int offset;

    /**
     * Keyword of this entity. For example: {@code "PrimeMeridian"}.
     */
    public final String keyword;

    /**
     * An ordered list of {@link String}s, {@link Number}s and other {@link Element}s.
     * May be {@code null} if the keyword was not followed by a pair of brackets (e.g. "north").
     */
    private final Deque<Object> list;

    /**
     * The locale to be used for formatting an error message if the parsing fails, or {@code null} for
     * the system default. This is <strong>not</strong> the locale for parting number or date values.
     */
    private final Locale locale;

    /**
     * Constructs a root element.
     *
     * @param singleton The only children for this root.
     */
    Element(final Element singleton) {
        offset  = 0;
        keyword = null;
        locale  = singleton.locale;
        list    = new LinkedList<>();   // Needs to be a modifiable list.
        list.add(singleton);
    }

    /**
     * Constructs a new {@code Element}.
     *
     * @param text     The text to parse.
     * @param position On input, the position where to start parsing from.
     *                 On output, the first character after the separator.
     */
    Element(final Parser parser, final String text, final ParsePosition position) throws ParseException {
        /*
         * Find the first keyword in the specified string. If a keyword is found, then
         * the position is set to the index of the first character after the keyword.
         */
        locale = parser.errorLocale;
        offset = position.getIndex();
        final int length = text.length();
        int lower = skipLeadingWhitespaces(text, offset, length);
        { // This block is for keeping some variables local.
            int c = text.codePointAt(lower);
            if (!Character.isUnicodeIdentifierStart(c)) {
                keyword = text;
                position.setErrorIndex(lower);
                throw unparsableString(text, position);
            }
            int upper = lower;
            while ((upper += Character.charCount(c)) < length) {
                c = text.codePointAt(upper);
                if (!Character.isUnicodeIdentifierPart(c)) break;
            }
            keyword = text.substring(lower, upper);
            lower = skipLeadingWhitespaces(text, upper, length);
        }
        int valueType = 0;
        /*
         * At this point we have extracted the keyword (e.g. "PrimeMeridian"). Now parse the opening bracket.
         * According WKT's specification, two characters are acceptable: '[' and '('. We accept both, but we
         * will require the matching closing bracket at the end of this method. For example if the opening
         * bracket was '[', then we will require that the closing bracket is ']' and not ')'.
         */
        final int openingBracket;
        final int closingBracket;
        if (lower >= length || (closingBracket = parser.symbols.matchingBracket(
                                openingBracket = text.codePointAt(lower))) < 0)
        {
            position.setIndex(lower);
            list = null;
            return;
        }
        lower = skipLeadingWhitespaces(text, lower + Character.charCount(openingBracket), length);
        /*
         * Parse all elements inside the bracket. Elements are parsed sequentially
         * and their type are selected according their first character:
         *
         *   - If the first character is a quote, then the value is returned as a String.
         *   - Otherwise, if the first character is a unicode identifier start, then the element is parsed as a chid Element.
         *   - Otherwise, if the characters are "true" of "false" (ignoring case), then the value is returned as a boolean.
         *   - Otherwise, the element is parsed as a number or as a date, depending of 'isTemporal' boolean value.
         */
        list = new LinkedList<>();
        final String separator = parser.symbols.trimmedSeparator();
        while (lower < length) {
            final int firstChar = text.codePointAt(lower);
            final int closingQuote = parser.symbols.matchingQuote(firstChar);
            if (closingQuote >= 0) {
                /*
                 * Try to parse the next element as a quoted string. We will take it as a string if the first non-blank
                 * character is a quote.  Note that a double quote means that the quote should be included as-is in the
                 * parsed text.
                 */
                final int n = Character.charCount(closingQuote);
                lower += Character.charCount(firstChar) - n;    // This will usually let 'lower' unchanged.
                CharSequence content = null;
                do {
                    final int upper = text.indexOf(closingQuote, lower += n);
                    if (upper < lower) {
                        position.setIndex(offset);
                        position.setErrorIndex(lower);
                        throw missingCharacter(closingQuote, lower);
                    }
                    if (content == null) {
                        content = text.substring(lower, upper);   // First text fragment, and usually the only one.
                    } else {
                        /*
                         * We will enter in this block only if we found at least one double quote.
                         * Convert the first text fragment to a StringBuilder so we can concatenate
                         * the next text fragments with only one quote between them.
                         */
                        if (content instanceof String) {
                            content = new StringBuilder((String) content);
                        }
                        ((StringBuilder) content).appendCodePoint(closingQuote).append(text, lower, upper);
                    }
                    lower = upper + n;  // After the closing quote.
                } while (lower < text.length() && text.codePointAt(lower) == closingQuote);
                list.add(content.toString());
            } else if (!Character.isUnicodeIdentifierStart(firstChar)) {
                /*
                 * Try to parse the next element as a date or a number. We will attempt such parsing
                 * if the first non-blank character is not the beginning of an unicode identifier.
                 * Otherwise we will assume that the next element is the keyword of a child 'Element'.
                 */
                position.setIndex(lower);
                final Object value;
                if (valueType == 0) {
                    valueType = ArraysExt.containsIgnoreCase(TIME_KEYWORDS, keyword) ? TEMPORAL : NUMERIC;
                }
                switch (valueType) {
                    case TEMPORAL: value = parser.parseDate  (text, position); break;
                    case NUMERIC:  value = parser.parseNumber(text, position); break;
                    default: throw new AssertionError(valueType);  // Should never happen.
                }
                if (value == null) {
                    position.setIndex(offset);
                    // Do not update the error index; it is already updated by NumberFormat.
                    throw unparsableString(text, position);
                }
                list.add(value);
                lower = position.getIndex();
            } else if (lower != (lower = regionMatches(text, lower, "true"))) {
                list.add(Boolean.TRUE);
            } else if (lower != (lower = regionMatches(text, lower, "false"))) {
                list.add(Boolean.FALSE);
            } else {
                // Otherwise, add the element as a child element.
                position.setIndex(lower);
                list.add(new Element(parser, text, position));
                lower = position.getIndex();
            }
            /*
             * At this point we finished to parse the component. If we find a separator (usually a coma),
             * search for another element. Otherwise verify that the closing bracket is present.
             */
            lower = skipLeadingWhitespaces(text, lower, length);
            if (text.regionMatches(lower, separator, 0, separator.length())) {
                lower = skipLeadingWhitespaces(text, lower + separator.length(), length);
            } else {
                if (lower >= length) break;
                final int c = text.codePointAt(lower);
                if (c == closingBracket) {
                    position.setIndex(lower + Character.charCount(c));
                    return;
                }
                position.setIndex(offset);
                position.setErrorIndex(lower);
                throw unparsableString(text, position);
            }
        }
        position.setIndex(offset);
        position.setErrorIndex(lower);
        throw missingCharacter(closingBracket, lower);
    }

    /**
     * Increments the given {@code index} if and only if the word at that position is the given word,
     * ignoring case. Otherwise returns the index unchanged.
     */
    private static int regionMatches(final String text, final int index, final String word) {
        if (text.regionMatches(true, index, word, 0, word.length())) {
            final int end = index + word.length();
            if (end >= text.length() || !Character.isUnicodeIdentifierPart(text.codePointAt(end))) {
                return end;
            }
        }
        return index;
    }




    ////////////////////////////////////////////////////////////////////////////////////////
    ////////                                                                        ////////
    ////////    Construction of a ParseException when a string can not be parsed    ////////
    ////////                                                                        ////////
    ////////////////////////////////////////////////////////////////////////////////////////

    /**
     * Returns a {@link ParseException} for a child keyword which is unknown.
     *
     * @param  child The unknown child keyword, or {@code null}.
     * @return The exception to be thrown.
     */
    final ParseException keywordNotFound(String child) {
        if (child == null) {
            child = "";
        }
        return new LocalizedParseException(locale, Errors.Keys.UnknownKeyword_1, new String[] {child}, offset);
    }

    /**
     * Returns a {@link ParseException} with the specified cause. A localized string
     * <code>"Error in &lt;{@link #keyword}&gt;"</code> will be prepend to the message.
     * The error index will be the starting index of this {@code Element}.
     *
     * @param  cause The cause of the failure, or {@code null} if none.
     * @return The exception to be thrown.
     */
    final ParseException parseFailed(final Exception cause) {
        return (ParseException) new LocalizedParseException(locale, Errors.Keys.ErrorIn_2,
                new String[] {keyword, Exceptions.getLocalizedMessage(cause, locale)}, offset).initCause(cause);
    }

    /**
     * Returns a {@link ParseException} with a "Unparsable string" message.
     * The error message is built from the specified string starting at the specified position.
     * Properties {@link ParsePosition#getIndex()} and {@link ParsePosition#getErrorIndex()}
     * must be accurate before this method is invoked.
     *
     * @param  text The unparsable string.
     * @param  position The position in the string.
     * @return An exception with a formatted error message.
     */
    private ParseException unparsableString(final String text, final ParsePosition position) {
        final short errorKey;
        final CharSequence[] arguments;
        final int errorIndex = Math.max(position.getIndex(), position.getErrorIndex());
        final int length = text.length();
        if (errorIndex == length) {
            errorKey  = Errors.Keys.UnexpectedEndOfString_1;
            arguments = new String[] {keyword};
        } else {
            errorKey  = Errors.Keys.UnparsableStringInElement_2;
            arguments = new CharSequence[] {keyword, CharSequences.token(text, errorIndex)};
        }
        return new LocalizedParseException(locale, errorKey, arguments, errorIndex);
    }

    /**
     * Returns an exception saying that a character is missing.
     *
     * @param c The missing character.
     * @param position The error position.
     */
    private ParseException missingCharacter(final int c, final int position) {
        final StringBuilder buffer = new StringBuilder(2).appendCodePoint(c);
        return new LocalizedParseException(locale, Errors.Keys.MissingCharacterInElement_2,
                new CharSequence[] {keyword, buffer}, position);
    }

    /**
     * Returns an exception saying that a component is missing.
     *
     * @param key The name of the missing component.
     */
    private ParseException missingParameter(final String key) {
        int error = offset;
        if (keyword != null) {
            error += keyword.length();
        }
        return new LocalizedParseException(locale, Errors.Keys.MissingComponentInElement_2,
                new String[] {keyword, key}, error);
    }




    //////////////////////////////////////////////////////////////////////////////////////
    ////////                                                                      ////////
    ////////    Pull elements from the tree                                       ////////
    ////////                                                                      ////////
    //////////////////////////////////////////////////////////////////////////////////////

    /**
     * Removes the next {@link Date} from the list and returns it.
     *
     * @param  key The parameter name. Used for formatting an error message if no date is found.
     * @return The next {@link Date} on the list.
     * @throws ParseException if no more date is available.
     */
    public Date pullDate(final String key) throws ParseException {
        final Iterator<Object> iterator = list.iterator();
        while (iterator.hasNext()) {
            final Object object = iterator.next();
            if (object instanceof Date) {
                iterator.remove();
                return (Date) object;
            }
        }
        throw missingParameter(key);
    }

    /**
     * Removes the next {@link Number} from the list and returns it.
     *
     * @param  key The parameter name. Used for formatting an error message if no number is found.
     * @return The next {@link Number} on the list as a {@code double}.
     * @throws ParseException if no more number is available.
     */
    public double pullDouble(final String key) throws ParseException {
        final Iterator<Object> iterator = list.iterator();
        while (iterator.hasNext()) {
            final Object object = iterator.next();
            if (object instanceof Number) {
                iterator.remove();
                return ((Number) object).doubleValue();
            }
        }
        throw missingParameter(key);
    }

    /**
     * Removes the next {@link Number} from the list and returns it as an integer.
     *
     * @param  key The parameter name. Used for formatting an error message if no number is found.
     * @return The next {@link Number} on the list as an {@code int}.
     * @throws ParseException if no more number is available, or the number is not an integer.
     */
    public int pullInteger(final String key) throws ParseException {
        final Iterator<Object> iterator = list.iterator();
        while (iterator.hasNext()) {
            final Object object = iterator.next();
            if (object instanceof Number) {
                iterator.remove();
                final Number number = (Number) object;
                if (number instanceof Float || number instanceof Double) {
                    throw new LocalizedParseException(locale, Errors.Keys.UnparsableStringForClass_2,
                            new Object[] {Integer.class, number}, offset);
                }
                return number.intValue();
            }
        }
        throw missingParameter(key);
    }

    /**
     * Removes the next {@link Boolean} from the list and returns it.
     *
     * @param  key The parameter name. Used for formatting an error message if no boolean is found.
     * @return The next {@link Boolean} on the list as a {@code boolean}.
     * @throws ParseException if no more boolean is available.
     */
    public boolean pullBoolean(final String key) throws ParseException {
        final Iterator<Object> iterator = list.iterator();
        while (iterator.hasNext()) {
            final Object object = iterator.next();
            if (object instanceof Boolean) {
                iterator.remove();
                return (Boolean) object;
            }
        }
        throw missingParameter(key);
    }

    /**
     * Removes the next {@link String} from the list and returns it.
     *
     * @param  key The parameter name. Used for formatting an error message if no number is found.
     * @return The next {@link String} on the list.
     * @throws ParseException if no more string is available.
     */
    public String pullString(final String key) throws ParseException {
        final Iterator<Object> iterator = list.iterator();
        while (iterator.hasNext()) {
            final Object object = iterator.next();
            if (object instanceof String) {
                iterator.remove();
                return (String) object;
            }
        }
        throw missingParameter(key);
    }

    /**
     * Removes the next {@link Object} from the list and returns it.
     *
     * @param  key The parameter name. Used for formatting an error message if no number is found.
     * @return The next {@link Object} on the list (never {@code null}).
     * @throws ParseException if no more object is available.
     */
    public Object pullObject(final String key) throws ParseException {
        final Iterator<Object> iterator = list.iterator();
        while (iterator.hasNext()) {
            final Object object = iterator.next();
            if (object != null) {
                iterator.remove();
                return object;
            }
        }
        throw missingParameter(key);
    }

    /**
     * Removes the next {@link Element} from the list and returns it.
     *
     * @param  key The element name (e.g. {@code "PrimeMeridian"}).
     * @return The next {@link Element} on the list.
     * @throws ParseException if no more element is available.
     */
    public Element pullElement(final String key) throws ParseException {
        final Element element = pullOptionalElement(key, null);
        if (element != null) {
            return element;
        }
        throw missingParameter(key);
    }

    /**
     * Removes the next {@link Element} from the list and returns it.
     *
     * @param  key    The element name (e.g. {@code "PrimeMeridian"}).
     * @param  aktKey An alternative key, or {@code null} if none.
     * @return The next {@link Element} on the list, or {@code null} if no more element is available.
     */
    public Element pullOptionalElement(final String key, final String altKey) {
        final Iterator<Object> iterator = list.iterator();
        while (iterator.hasNext()) {
            final Object object = iterator.next();
            if (object instanceof Element) {
                final Element element = (Element) object;
                if (element.list != null && (key.equalsIgnoreCase(element.keyword) ||
                       (altKey != null && altKey.equalsIgnoreCase(element.keyword))))
                {
                    iterator.remove();
                    return element;
                }
            }
        }
        return null;
    }

    /**
     * Removes and returns the next {@link Element} with no bracket.
     * The key is used only for only for formatting an error message.
     *
     * @param  key The parameter name. Used only for formatting an error message.
     * @return The next {@link Element} in the list, with no bracket.
     * @throws ParseException if no more void element is available.
     */
    public Element pullVoidElement(final String key) throws ParseException {
        final Iterator<Object> iterator = list.iterator();
        while (iterator.hasNext()) {
            final Object object = iterator.next();
            if (object instanceof Element) {
                final Element element = (Element) object;
                if (element.list == null) {
                    iterator.remove();
                    return element;
                }
            }
        }
        throw missingParameter(key);
    }

    /**
     * Returns the next element, or {@code null} if there is no more element.
     * The element is <strong>not</strong> removed from the list.
     *
     * @return The next element, or {@code null} if there is no more elements.
     */
    public Object peek() {
        return list.isEmpty() ? null : list.getFirst();
    }

    /**
     * Returns {@code true} if this element does not contains any remaining child.
     *
     * @return {@code true} if there is no child remaining.
     */
    public boolean isEmpty() {
        return list.isEmpty();
    }

    /**
     * Closes this element. This method verifies that there is no unprocessed value (dates,
     * numbers, booleans or strings), but ignores inner elements as required by ISO 19162.
     *
     * If the given {@code ignored} map is non-null, then this method will add the keywords
     * of ignored elements in that map as below:
     * <ul>
     *   <li><b>Keys</b>: keyword of ignored elements. Note that a key may be null.</li>
     *   <li><b>Values</b>: keywords of all elements containing an element identified by the above-cited key.
     *       This list is used for helping the users to locate the ignored elements.</li>
     * </ul>
     *
     * @param  ignoredElements The collection where to declare ignored elements, or {@code null}.
     * @throws ParseException If the list still contains some unprocessed values.
     */
    final void close(final Map<String, List<String>> ignoredElements) throws ParseException {
        if (list != null) {
            for (final Object value : list) {
                if (value instanceof Element) {
                    if (ignoredElements != null) {
                        CollectionsExt.addToMultiValuesMap(ignoredElements, ((Element) value).keyword, keyword);
                    }
                } else {
                    throw new LocalizedParseException(locale, Errors.Keys.UnexpectedValueInElement_2,
                            new Object[] {keyword, value}, offset + keyword.length());
                }
            }
        }
    }

    /**
     * Formats this {@code Element} as a tree.
     * This method is used for debugging purpose only.
     */
    @Debug
    @Override
    public String toString() {
        final StringBuilder buffer = new StringBuilder();
        format(buffer, 0, System.lineSeparator());
        return buffer.toString();
    }

    /**
     * Implementation of {@link #toString()} to be invoked recursively.
     *
     * @param buffer Where to format.
     * @param margin Number of space to put in the left margin.
     */
    @Debug
    private void format(final StringBuilder buffer, int margin, final String lineSeparator) {
        buffer.append(CharSequences.spaces(margin)).append(keyword);
        if (list != null) {
            buffer.append('[');
            margin += 4;
            boolean addSeparator = false;
            for (final Object value : list) {
                if (value instanceof Element) {
                    if (addSeparator) buffer.append(',');
                    buffer.append(lineSeparator);
                    ((Element) value).format(buffer, margin, lineSeparator);
                } else {
                    final boolean quote = (value instanceof CharSequence);
                    if (addSeparator) buffer.append(", ");
                    if (quote) buffer.append('“');
                    buffer.append(value);
                    if (quote) buffer.append('”');
                }
                addSeparator = true;
            }
            buffer.append(']');
        }
    }
}
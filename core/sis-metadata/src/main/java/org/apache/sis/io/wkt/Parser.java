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

import java.util.Map;
import java.util.List;
import java.util.Date;
import java.util.Locale;
import java.util.LinkedHashMap;
import java.text.DateFormat;
import java.text.NumberFormat;
import java.text.DecimalFormat;
import java.text.ParsePosition;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import org.opengis.util.FactoryException;
import org.opengis.util.InternationalString;
import org.apache.sis.util.Workaround;
import org.apache.sis.util.Exceptions;
import org.apache.sis.util.resources.Errors;
import org.apache.sis.util.resources.Vocabulary;
import org.apache.sis.internal.metadata.WKTParser;
import org.apache.sis.util.iso.AbstractInternationalString;

import static org.apache.sis.util.ArgumentChecks.ensureNonNull;


/**
 * Parses <cite>Well Known Text</cite> (WKT). Parsers are the converse of {@link Formatter}.
 * Like the later, a parser is constructed with a given set of {@linkplain Symbols symbols}.
 * Parsers also need a set of factories to be used for instantiating the parsed objects.
 *
 * <p>In current version, parsers are usually not intended to be subclassed outside this package.</p>
 *
 * <p>Parsers are not synchronized. It is recommended to create separate parser instances for each thread.
 * If multiple threads access a parser concurrently, it must be synchronized externally.</p>
 *
 * @author  Rémi Eve (IRD)
 * @author  Martin Desruisseaux (IRD, Geomatys)
 * @since   0.6
 * @version 0.6
 * @module
 */
abstract class Parser implements WKTParser {
    /**
     * Set to {@code true} if parsing of number in scientific notation is allowed.
     * The way to achieve that is currently a hack, because {@link NumberFormat}
     * has no API for managing that as of JDK 1.8.
     *
     * @todo See if a future version of JDK allows us to get ride of this ugly hack.
     */
    @Workaround(library = "JDK", version = "1.8")
    static final boolean SCIENTIFIC_NOTATION = true;

    /**
     * The locale for error messages (not for number parsing), or {@code null} for the system default.
     */
    final Locale errorLocale;

    /**
     * The symbols to use for parsing WKT.
     */
    final Symbols symbols;

    /**
     * The symbol for scientific notation, or {@code null} if none.
     * This is usually {@code "E"} (note the upper case), but could also be something like {@code "×10^"}.
     */
    private final String exponentSymbol;

    /**
     * The object to use for parsing numbers.
     */
    private final NumberFormat numberFormat;

    /**
     * The object to use for parsing dates, created when first needed.
     */
    private DateFormat dateFormat;

    /**
     * Keyword of unknown elements. The ISO 19162 specification requires that we ignore unknown elements,
     * but we will nevertheless report them as warnings.
     * The meaning of this map is:
     * <ul>
     *   <li><b>Keys</b>: keyword of ignored elements. Note that a key may be null.</li>
     *   <li><b>Values</b>: keywords of all elements containing an element identified by the above-cited key.
     *       This list is used for helping the users to locate the ignored elements.</li>
     * </ul>
     */
    final Map<String, List<String>> ignoredElements;

    /**
     * The first warning (other than {@link #ignoredElements}) that occurred during the parsing.
     * Stored as an {@link InternationalString} in order to defer the actual message formatting until needed.
     * This is reset to {@code null} when a new parsing start.
     */
    private InternationalString warning;

    /**
     * Constructs a parser using the specified set of symbols.
     *
     * @param symbols       The set of symbols to use.
     * @param numberFormat  The number format provided by {@link WKTFormat}, or {@code null} for a default format.
     * @param dateFormat    The date format provided by {@link WKTFormat}, or {@code null} for a default format.
     * @param errorLocale   The locale for error messages (not for parsing), or {@code null} for the system default.
     */
    Parser(final Symbols symbols, NumberFormat numberFormat, final DateFormat dateFormat, final Locale errorLocale) {
        ensureNonNull("symbols", symbols);
        if (numberFormat == null) {
            numberFormat = symbols.createNumberFormat();
        }
        this.symbols      = symbols;
        this.numberFormat = numberFormat;
        this.dateFormat   = dateFormat;
        this.errorLocale  = errorLocale;
        if (SCIENTIFIC_NOTATION && numberFormat instanceof DecimalFormat) {
            final DecimalFormat decimalFormat = (DecimalFormat) numberFormat;
            exponentSymbol = decimalFormat.getDecimalFormatSymbols().getExponentSeparator();
            String pattern = decimalFormat.toPattern();
            if (!pattern.contains("E0")) {
                final StringBuilder buffer = new StringBuilder(pattern);
                final int split = pattern.indexOf(';');
                if (split >= 0) {
                    buffer.insert(split, "E0");
                }
                buffer.append("E0");
                decimalFormat.applyPattern(buffer.toString());
            }
        } else {
            exponentSymbol = null;
        }
        ignoredElements = new LinkedHashMap<>();
    }

    /**
     * Creates the object from a string. This method is for implementation of {@code createFromWKT(String)}
     * method is SIS factories only.
     *
     * @param  text Coordinate system encoded in Well-Known Text format (version 1 or 2).
     * @return The result of parsing the given text.
     * @throws FactoryException if the object creation failed.
     *
     * @see org.apache.sis.referencing.factory.GeodeticObjectFactory#createFromWKT(String)
     * @see org.apache.sis.referencing.operation.transform.DefaultMathTransformFactory#createFromWKT(String)
     */
    @Override
    public final Object createFromWKT(final String text) throws FactoryException {
        try {
            return parseObject(text, new ParsePosition(0));
        } catch (ParseException exception) {
            final Throwable cause = exception.getCause();
            if (cause instanceof FactoryException) {
                throw (FactoryException) cause;
            }
            throw new FactoryException(exception);
        }
    }

    /**
     * Parses a <cite>Well Know Text</cite> (WKT).
     *
     * @param  text The text to be parsed.
     * @param  position The position to start parsing from.
     * @return The parsed object.
     * @throws ParseException if the string can not be parsed.
     */
    public Object parseObject(final String text, final ParsePosition position) throws ParseException {
        warning = null;
        ignoredElements.clear();
        final Element element = new Element(new Element(this, text, position));
        final Object object = parseObject(element);
        element.close(ignoredElements);
        return object;
    }

    /**
     * Parses the next element in the specified <cite>Well Know Text</cite> (WKT) tree.
     *
     * @param  element The element to be parsed.
     * @return The parsed object.
     * @throws ParseException if the element can not be parsed.
     */
    abstract Object parseObject(final Element element) throws ParseException;

    /**
     * Parses the number at the given position.
     * This is a helper method for {@link Element} only.
     */
    final Number parseNumber(String text, final ParsePosition position) {
        final int base = position.getIndex();
        Number number = numberFormat.parse(text, position);
        if (number != null && exponentSymbol != null) {
            /*
             * HACK: DecimalFormat.parse(…) does not understand lower case 'e' for scientific notation.
             *       It understands upper case 'E' only, so we may need to perform a replacement here.
             */
            int i = position.getIndex();
            if (text.regionMatches(true, i, exponentSymbol, 0, exponentSymbol.length())) {
                text = new StringBuilder(text).replace(i, i + exponentSymbol.length(), exponentSymbol).toString();
                position.setIndex(base);
                number = numberFormat.parse(text, position);
            }
        }
        return number;
    }

    /**
     * Parses the date at the given position.
     * This is a helper method for {@link Element} only.
     */
    final Date parseDate(final String text, final ParsePosition position) {
        if (dateFormat == null) {
            dateFormat = new SimpleDateFormat(WKTFormat.DATE_PATTERN, symbols.getLocale());
        }
        return dateFormat.parse(text, position);
    }

    /**
     * Reports a non-fatal warning that occurred while parsing a WKT.
     *
     * @param parent  The parent element.
     * @param keyword The element that we can not parse.
     * @param ex      The non-fatal exception that occurred while parsing the element, or {@code null} if none.
     */
    final void warning(final Element parent, final Element element, final Exception ex) {
        if (warning == null) {
            warning = new AbstractInternationalString() {
                /**
                 * Formats the error message only when requested.
                 * In many cases, this method is never invoked.
                 */
                @Override
                public String toString(final Locale locale) {
                    CharSequence  text   = null;
                    StringBuilder buffer = null;
                    final Errors resources = Errors.getResources(locale);
                    if (element != null) {  // Should be null only if 'warning' has been invoked by 'getAndClearWarning'.
                        text = resources.getString(Errors.Keys.UnparsableStringInElement_2, parent.keyword, element.keyword);
                        final String message = Exceptions.getLocalizedMessage(ex, locale);
                        if (message != null) {
                            text = buffer = new StringBuilder(text).append(' ').append(message);
                        }
                    }
                    /*
                     * If the parser has found some unknown elements, formats a bullet list for them.
                     */
                    if (!ignoredElements.isEmpty()) {
                        final String lineSeparator = System.lineSeparator();
                        if (buffer == null) {
                            buffer = new StringBuilder(250);
                            if (text != null) {
                                buffer.append(text).append(lineSeparator);
                            }
                            text = buffer;
                        } else {
                            buffer.append(lineSeparator);
                        }
                        final Vocabulary vocabulary = Vocabulary.getResources(locale);
                        buffer.append(resources.getString(Errors.Keys.UnknownElementsInText));
                        for (final Map.Entry<String, List<String>> entry : ignoredElements.entrySet()) {
                            buffer.append(lineSeparator).append("  • ")
                                    .append(vocabulary.getString(Vocabulary.Keys.Quoted_1, entry.getKey()));
                            String separator = vocabulary.getString(Vocabulary.Keys.InBetweenWords);
                            for (final String p : entry.getValue()) {
                                buffer.append(separator).append(p);
                                separator = ", ";
                            }
                            buffer.append('.');
                        }
                    }
                    return String.valueOf(text);
                }
            };
        }
    }

    /**
     * Returns the warning, or {@code null} if none.
     * This method clears the warning message after the call.
     */
    final InternationalString getAndClearWarning() {
        if (warning == null && !ignoredElements.isEmpty()) {
            warning(null, null, null);  // Force the creation of the warning object.
        }
        InternationalString m = warning;
        warning = null;
        return m;
    }
}
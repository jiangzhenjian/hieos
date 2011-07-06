/*
 * @(#)XDSBridgeConfigXmlParser.java
 * Date 2011-07-06
 * Version 1.0
 * Author Jim Horner
 * Copyright (c)2011
 */


package com.vangent.hieos.services.xds.bridge.support;

import com.vangent.hieos.hl7v3util.model.subject.CodedValue;
import com.vangent.hieos.services.xds.bridge.mapper.ContentParserConfig;
import com.vangent.hieos.services.xds.bridge.mapper.ContentParserConfig
    .ContentParserConfigName;
import com.vangent.hieos.services.xds.bridge.mapper.ContentVariableName;
import com.vangent.hieos.services.xds.bridge.mapper.DocumentTypeMapping;
import com.vangent.hieos.services.xds.bridge.utils.CodedValueUtils;
import com.vangent.hieos.xutil.exception.XMLParserException;
import com.vangent.hieos.xutil.exception.XPathHelperException;
import com.vangent.hieos.xutil.xml.XMLParser;
import com.vangent.hieos.xutil.xml.XPathHelper;

import org.apache.axiom.om.OMElement;
import org.apache.commons.lang.StringUtils;
import org.apache.log4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.xml.namespace.QName;


/**
 * Class description
 *
 *
 * @version        v1.0, 2011-07-06
 * @author         Jim Horner
 */
public class XDSBridgeConfigXmlParser {

    /** Field description */
    private static final Logger logger =
        Logger.getLogger(XDSBridgeConfigXmlParser.class);

    /** Field description */
    private final OMElement configElem;

    /** Field description */
    private final String defaultTemplate;

    /**
     * Constructs ...
     *
     *
     * @param filename
     */
    public XDSBridgeConfigXmlParser(String filename, String defaultTemplate)
            throws XMLParserException {

        super();

        this.configElem = XMLParser.fileToOM(filename);
        this.defaultTemplate = defaultTemplate;
    }

    /**
     * Method description
     *
     *
     * @return
     */
    public List<DocumentTypeMapping> parse() throws XPathHelperException {

        Map<String, ContentParserConfig> parserConfigs = parseContentConfigs();

        List<DocumentTypeMapping> mappings =
            parseDocumentTypeMappings(parserConfigs);

        return mappings;
    }

    /**
     * Method description
     *
     *
     * @param parserConfigElem
     *
     * @return
     */
    private ContentParserConfig parseContentConfig(
            ContentParserConfigName name, OMElement parserConfigElem) {

        // pull template or use default
        String templateFilename =
            parserConfigElem.getAttributeValue(new QName("template"));

        if (StringUtils.isBlank(templateFilename)) {

            templateFilename = this.defaultTemplate;
        }

        OMElement namespacesElem =
            parserConfigElem.getFirstChildWithName(new QName("Namespaces"));
        Map<String, String> namespaces = null;

        if (namespacesElem != null) {

            // pull namespaces
            namespaces = parseNameValuePairs(namespacesElem, "prefix", "uri",
                                             false);
        }

        OMElement dynamicElem = parserConfigElem.getFirstChildWithName(
                                    new QName("DocumentContentVariables"));
        Map<String, String> expressions = null;

        if (dynamicElem != null) {

            // pull expressions
            expressions = parseNameValuePairs(dynamicElem, "name",
                                              "expression", true);
        }

        OMElement staticElem = parserConfigElem.getFirstChildWithName(
                                   new QName("StaticContentVariables"));
        Map<String, String> staticValues = null;

        if (staticElem != null) {

            // pull static values
            staticValues = parseNameValuePairs(staticElem, "name", "value",
                                               true);
        }

        return new ContentParserConfig(name, namespaces, expressions,
                                       staticValues, templateFilename);
    }

    /**
     * Method description
     *
     *
     *
     * @return
     *
     */
    private Map<String, ContentParserConfig> parseContentConfigs()
            throws XPathHelperException {

        Map<String, ContentParserConfig> result = new HashMap<String,
                                                      ContentParserConfig>();

        QName nameQName = new QName("name");
        QName baseQName = new QName("base");

        Iterator<OMElement> iterator = this.configElem.getChildrenWithName(
                                           new QName("ContentParserConfig"));

        while (iterator.hasNext()) {

            OMElement parserConfigElem = iterator.next();

            // pull name
            String nameAttribute =
                parserConfigElem.getAttributeValue(nameQName);

            ContentParserConfigName name = null;

            try {

                name = ContentParserConfigName.valueOf(nameAttribute);

            } catch (IllegalArgumentException e) {

                // ignore, probably a base config
                logger.warn(String.format("Ignoring %s, probably a base.",
                                          nameAttribute));
            }

            if (name != null) {

                ContentParserConfig parserConfig = parseContentConfig(name,
                                                       parserConfigElem);

                // pull base value
                String baseAttribute =
                    parserConfigElem.getAttributeValue(baseQName);

                if (StringUtils.isNotBlank(baseAttribute)) {

                    String expr =
                        String.format("//ContentParserConfig[@name='%s']",
                                      baseAttribute);
                    OMElement baseElem =
                        XPathHelper.selectSingleNode(this.configElem, expr, "");

                    if (baseElem != null) {

                        ContentParserConfig baseConfig =
                            parseContentConfig(name, baseElem);

                        // need to merge
                        baseConfig.merge(parserConfig);
                        parserConfig = baseConfig;

                    } else {

                        logger.warn(String.format("Base %s is not found.",
                                                  baseAttribute));
                    }
                }

                result.put(name.toString(), parserConfig);
            }
        }

        return result;
    }

    /**
     * Method description
     *
     *
     * @param parserConfigs
     *
     * @return
     */
    private List<DocumentTypeMapping> parseDocumentTypeMappings(Map<String,
                 ContentParserConfig> parserConfigs) {

        List<DocumentTypeMapping> result = new ArrayList<DocumentTypeMapping>();

        OMElement docMappingsElem = this.configElem.getFirstChildWithName(
                                        new QName("DocumentTypeMappings"));

        Iterator<OMElement> iterator = docMappingsElem.getChildElements();

        QName nameQName = new QName("name");
        QName parserConfigQName = new QName("ContentParserConfig");
        QName formatQName = new QName("Format");
        QName mimeQName = new QName("MimeType");
        QName typeQName = new QName("type");

        while (iterator.hasNext()) {

            OMElement mappingElem = iterator.next();

            OMElement xdsMapperElem =
                mappingElem.getFirstChildWithName(parserConfigQName);
            String parserConfigName =
                xdsMapperElem.getAttributeValue(nameQName);

            OMElement mimeTypeElem =
                mappingElem.getFirstChildWithName(mimeQName);
            String mimeType = mimeTypeElem.getAttributeValue(typeQName);

            ContentParserConfig parserConfig =
                parserConfigs.get(parserConfigName);

            if (parserConfig == null) {

                throw new IllegalStateException(
                    String.format(
                        "ContentParserConfig name=%s does not exist.",
                        parserConfigName));
            }

            CodedValue type = CodedValueUtils.parseCodedValue(mappingElem);

            OMElement formatElem =
                mappingElem.getFirstChildWithName(formatQName);

            CodedValue format = CodedValueUtils.parseCodedValue(formatElem);

            result.add(new DocumentTypeMapping(type, format, mimeType,
                                               parserConfig));
        }

        return result;
    }

    /**
     * Method description
     *
     *
     * @param node
     * @param nameAttribute
     * @param valueAttribute
     * @param checkKey
     *
     * @return
     */
    private Map<String, String> parseNameValuePairs(OMElement node,
            String nameAttribute, String valueAttribute, boolean checkKey) {

        Map<String, String> result = new LinkedHashMap<String, String>();

        QName nameQName = new QName(nameAttribute);
        QName valueQName = new QName(valueAttribute);

        Iterator<OMElement> iterator = node.getChildElements();

        while (iterator.hasNext()) {

            OMElement childNode = iterator.next();
            String key = childNode.getAttributeValue(nameQName);

            if (checkKey) {

                try {

                    ContentVariableName varName =
                        ContentVariableName.valueOf(key);

                    key = varName.toString();

                } catch (IllegalArgumentException e) {

                    logger.warn(String.format("%s is not valid.", key));
                    key = null;
                }
            }

            // checkKey will null out a bad key
            if (StringUtils.isNotBlank(key)) {

                String value = childNode.getAttributeValue(valueQName);

                result.put(key, value);
            }
        }

        return result;
    }
}

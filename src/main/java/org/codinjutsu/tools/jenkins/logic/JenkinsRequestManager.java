/*
 * Copyright (c) 2012 David Boissier
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.codinjutsu.tools.jenkins.logic;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.log4j.Logger;
import org.codinjutsu.tools.jenkins.JenkinsConfiguration;
import org.codinjutsu.tools.jenkins.exception.ConfigurationException;
import org.codinjutsu.tools.jenkins.model.*;
import org.codinjutsu.tools.jenkins.security.SecurityClient;
import org.codinjutsu.tools.jenkins.security.SecurityClientFactory;
import org.codinjutsu.tools.jenkins.security.SecurityMode;
import org.codinjutsu.tools.jenkins.util.RssUtil;
import org.jdom.Document;
import org.jdom.Element;
import org.jdom.JDOMException;
import org.jdom.input.SAXBuilder;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.net.URL;
import java.util.*;

public class JenkinsRequestManager {

    private static final String JENKINS_DESCRIPTION = "description";

    private static final String JOB = "job";
    private static final String JOB_NAME = "name";
    private static final String JOB_HEALTH = "healthReport";
    private static final String JOB_HEALTH_ICON = "iconUrl";
    private static final String JOB_HEALTH_DESCRIPTION = "description";
    private static final String JOB_URL = "url";
    private static final String JOB_COLOR = "color";
    private static final String JOB_LAST_BUILD = "lastBuild";
    private static final String JOB_IS_BUILDABLE = "buildable";
    private static final String JOB_IS_IN_QUEUE = "inQueue";

    private static final String VIEW = "view";
    private static final String PRIMARY_VIEW = "primaryView";
    private static final String VIEW_NAME = "name";

    private static final String VIEW_URL = "url";
    private static final String BUILD_IS_BUILDING = "building";
    private static final String BUILD_ID = "id";
    private static final String BUILD_RESULT = "result";
    private static final String BUILD_URL = "url";
    private static final String BUILD_NUMBER = "number";

    private static final String PARAMETER_PROPERTY = "property";
    private static final String PARAMETER_DEFINITION = "parameterDefinition";
    private static final String PARAMETER_NAME = "name";
    private static final String PARAMETER_TYPE = "type";
    private static final String PARAMETER_DEFAULT_PARAM = "defaultParameterValue";
    private static final String PARAMETER_DEFAULT_PARAM_VALUE = "value";
    private static final String PARAMETER_CHOICE = "choice";

    private static final String RSS_ENTRY = "entry";
    private static final String RSS_TITLE = "title";
    private static final String RSS_LINK = "link";
    private static final String RSS_LINK_HREF = "href";

    private static final String RSS_PUBLISHED = "published";
    private static final String JENKINS_ROOT_TAG = "jenkins";
    private static final String HUDSON_ROOT_TAG = "hudson";
    private static final String FOLDER_ROOT_TAG = "folder";

    private static final Logger LOG = Logger.getLogger(JenkinsRequestManager.class);

    private final UrlBuilder urlBuilder;
    private SecurityClient securityClient;

    private JenkinsPlateform jenkinsPlateform = JenkinsPlateform.CLASSIC;
    private LoadViewStrategy loadViewStrategy = new LoadClassicViewStrategy();

    public enum JenkinsPlateform {
        CLASSIC,
        CLOUDBEES
    }

    private static final Set<String> ALLOWED_ROOT_TAGS = new HashSet<String>();

    static {
        ALLOWED_ROOT_TAGS.add(JENKINS_ROOT_TAG);
        ALLOWED_ROOT_TAGS.add(HUDSON_ROOT_TAG);
        ALLOWED_ROOT_TAGS.add(FOLDER_ROOT_TAG);
    }


    public JenkinsRequestManager(String crumbDataFile) {
        this(SecurityClientFactory.none(crumbDataFile));
    }


    public JenkinsRequestManager(SecurityClient securityClient) {
        this.urlBuilder = new UrlBuilder();
        this.securityClient = securityClient;
    }


    public Jenkins loadJenkinsWorkspace(JenkinsConfiguration configuration) {
        URL url = urlBuilder.createJenkinsWorkspaceUrl(configuration);
        String jenkinsWorkspaceData = securityClient.execute(url);

        Document doc = buildDocument(jenkinsWorkspaceData);

        Jenkins jenkins = createJenkins(doc, configuration.getServerUrl());
        jenkins.setPrimaryView(createPreferredView(doc));
        jenkins.setViews(createJenkinsViews(doc));

        int jenkinsPort = url.getPort();
        URL viewUrl = urlBuilder.createViewUrl(jenkinsPlateform, jenkins.getPrimaryView().getUrl());
        int viewPort = viewUrl.getPort();

        if (isJenkinsPortSet(jenkinsPort) && jenkinsPort != viewPort) {
            throw new ConfigurationException(String.format("Jenkins Port seems to be incorrect in the Server configuration page. Please fix 'Jenkins URL' at %s/configure", configuration.getServerUrl()));
        }

        return jenkins;
    }

    private static boolean isJenkinsPortSet(int jenkinsPort) {
        return jenkinsPort != -1;
    }


    public Map<String, Build> loadJenkinsRssLatestBuilds(JenkinsConfiguration configuration) {
        URL url = urlBuilder.createRssLatestUrl(configuration.getServerUrl());

        String rssData = securityClient.execute(url);
        Document doc = buildDocument(rssData);

        return createLatestBuildList(doc);
    }


    public List<Job> loadJenkinsView(String viewUrl) {
        URL url = urlBuilder.createViewUrl(jenkinsPlateform, viewUrl);
        String jenkinsViewData = securityClient.execute(url);
        Document doc = buildDocument(jenkinsViewData);
        return loadViewStrategy.loadJenkinsView(doc);
    }


    public Job loadJob(String jenkinsJobUrl) {
        URL url = urlBuilder.createJobUrl(jenkinsJobUrl);

        String jenkinsJobData = securityClient.execute(url);
        Document doc = buildDocument(jenkinsJobData);
        Element jobElement = doc.getRootElement();
        return createJob(jobElement);
    }

    private Document buildDocument(String jenkinsXmlData) {
        Reader jenkinsDataReader = new StringReader(jenkinsXmlData);
        try {
            return new SAXBuilder(false).build(jenkinsDataReader);
        } catch (JDOMException e) {
            LOG.error("Invalid data received from the Jenkins Server. Actual :\n" + jenkinsXmlData, e);
            throw new RuntimeException("Invalid data received from the Jenkins Server. Please retry");
        } catch (IOException e) {
            LOG.error("Error during analyzing the Jenkins data.", e);
            throw new RuntimeException("Error during analyzing the Jenkins data.");
        } finally {
            IOUtils.closeQuietly(jenkinsDataReader);
        }
    }


    public void runBuild(Job job, JenkinsConfiguration configuration) {
        URL url = urlBuilder.createRunJobUrl(job.getUrl(), configuration);
        securityClient.execute(url);
    }


    public void runParameterizedBuild(Job job, JenkinsConfiguration configuration, Map<String, String> paramValueMap) {
        URL url = urlBuilder.createRunParameterizedJobUrl(job.getUrl(), configuration, paramValueMap);
        securityClient.execute(url);
    }


    public void authenticate(final String serverUrl, SecurityMode securityMode, final String username, final String passwordFile, String crumbDataFile) {
        securityClient = SecurityClientFactory.create(securityMode, username, passwordFile, crumbDataFile);
        String jenkinsData = securityClient.connect(urlBuilder.createAuthenticationUrl(serverUrl));
        if (StringUtils.contains(jenkinsData, FOLDER_ROOT_TAG)) {
            jenkinsPlateform = JenkinsPlateform.CLOUDBEES;
            loadViewStrategy = new LoadCloudbeesViewStrategy();
        } else {
            jenkinsPlateform = JenkinsPlateform.CLASSIC;
            loadViewStrategy = new LoadClassicViewStrategy();
        }
    }


    private Jenkins createJenkins(Document doc, String serverUrl) {
        Element jenkinsElement = doc.getRootElement();
        if (!ALLOWED_ROOT_TAGS.contains(jenkinsElement.getName())) {
            throw new ConfigurationException(String.format("The root tag is should be %s. Actual: '%s'", ALLOWED_ROOT_TAGS, jenkinsElement.getName()));
        }
        String description = jenkinsElement.getChildText(JENKINS_DESCRIPTION);
        if (description == null) {
            description = "";
        }
        return new Jenkins(description, serverUrl);
    }


    private static Build createLastBuild(Element jobLastBuild) {
        String isBuilding = jobLastBuild.getChildText(BUILD_IS_BUILDING);
        String status = jobLastBuild.getChildText(BUILD_RESULT);
        String number = jobLastBuild.getChildText(BUILD_NUMBER);
        String buildUrl = jobLastBuild.getChildText(BUILD_URL);
        String date = jobLastBuild.getChildText(BUILD_ID);
        return Build.createBuildFromWorkspace(buildUrl, number, status, isBuilding, date);
    }


    private View createPreferredView(Document doc) {

        Element primaryView = doc.getRootElement().getChild(PRIMARY_VIEW);
        if (primaryView != null) {
            String viewName = primaryView.getChildText(VIEW_NAME);
            String viewUrl = primaryView.getChildText(VIEW_URL);
            return View.createView(viewName, viewUrl);
        }
        return null;
    }


    private List<View> createJenkinsViews(Document doc) {
        List<View> views = new ArrayList<View>();

        List<Element> viewElement = doc.getRootElement().getChildren(VIEW);
        for (Element element : viewElement) {
            String viewName = element.getChildText(VIEW_NAME);
            String viewUrl = element.getChildText(VIEW_URL);
            View view = View.createView(viewName, viewUrl);
            List<Element> subViewElements = element.getChildren(VIEW);
            if (subViewElements != null && !subViewElements.isEmpty()) {
                for (Element subViewElement : subViewElements) {
                    String subViewName = subViewElement.getChildText(VIEW_NAME);
                    String subViewUrl = subViewElement.getChildText(VIEW_URL);
                    view.addSubView(View.createNestedView(subViewName, subViewUrl));
                }
            }
            views.add(view);
        }

        return views;
    }


    private static void setJobParameters(Job job, List<Element> parameterDefinitions) {

        for (Element parameterDefinition : parameterDefinitions) {

            String paramName = parameterDefinition.getChildText(PARAMETER_NAME);
            String paramType = parameterDefinition.getChildText(PARAMETER_TYPE);

            String defaultParamValue = null;
            Element defaultParamElement = parameterDefinition.getChild(PARAMETER_DEFAULT_PARAM);
            if (defaultParamElement != null) {
                defaultParamValue = defaultParamElement.getChildText(PARAMETER_DEFAULT_PARAM_VALUE);
            }
            String[] choices = extractChoices(parameterDefinition);

            job.addParameter(paramName, paramType, defaultParamValue, choices);
        }
    }


    private static String[] extractChoices(Element parameterDefinition) {
        List<Element> choices = parameterDefinition.getChildren(PARAMETER_CHOICE);
        String[] paramValues = new String[0];
        if (choices != null && !choices.isEmpty()) {
            paramValues = new String[choices.size()];
            for (int i = 0; i < choices.size(); i++) {
                Element choice = choices.get(i);
                paramValues[i] = choice.getText();
            }
        }
        return paramValues;
    }

    private static Job createJob(Element jobElement) {
        String jobName = jobElement.getChildText(JOB_NAME);
        String jobColor = jobElement.getChildText(JOB_COLOR);
        String jobUrl = jobElement.getChildText(JOB_URL);
        String inQueue = jobElement.getChildText(JOB_IS_IN_QUEUE);
        String buildable = jobElement.getChildText(JOB_IS_BUILDABLE);

        Job job = Job.createJob(jobName, jobColor, jobUrl, inQueue, buildable);

        Job.Health jobHealth = getJobHealth(jobElement);
        if (jobHealth != null) {
            job.setHealth(jobHealth);
        }
        Element lastBuild = jobElement.getChild(JOB_LAST_BUILD);
        if (lastBuild != null) {
            job.setLastBuild(createLastBuild(lastBuild));
        }

        List<Element> propertyList = jobElement.getChildren(PARAMETER_PROPERTY);
        for (Element property : propertyList) {
            List parameterDefinitions = property.getChildren(PARAMETER_DEFINITION);
            if (!parameterDefinitions.isEmpty()) {
                setJobParameters(job, parameterDefinitions);
            }
        }
        return job;
    }


    private static Job.Health getJobHealth(Element jobElement) {
        String jobHealthLevel = null;
        String jobHealthDescription = null;
        Element jobHealthElement = jobElement.getChild(JOB_HEALTH);
        if (jobHealthElement != null) {
            jobHealthLevel = jobHealthElement.getChildText(JOB_HEALTH_ICON);
            if (StringUtils.isNotEmpty(jobHealthLevel)) {
                if (jobHealthLevel.endsWith(".png"))
                    jobHealthLevel = jobHealthLevel.substring(0, jobHealthLevel.lastIndexOf(".png"));
                else {
                    jobHealthLevel = jobHealthLevel.substring(0, jobHealthLevel.lastIndexOf(".gif"));
                }
            } else {
                jobHealthLevel = null;
            }

            jobHealthDescription = jobHealthElement.getChildText(JOB_HEALTH_DESCRIPTION);
        }

        if (!StringUtils.isEmpty(jobHealthLevel)) {
            return Job.Health.createHealth(jobHealthLevel, jobHealthDescription);
        }
        return null;
    }


    private Map<String, Build> createLatestBuildList(Document doc) {

        Map<String, Build> buildMap = new LinkedHashMap<String, Build>();
        Element rootElement = doc.getRootElement();

        List<Element> elements = rootElement.getChildren(RSS_ENTRY, rootElement.getNamespace());
        for (Element element : elements) {
            String title = element.getChildText(RSS_TITLE, rootElement.getNamespace());
            String publishedBuild = element.getChildText(RSS_PUBLISHED, rootElement.getNamespace());
            String jobName = RssUtil.extractBuildJob(title);
            String number = RssUtil.extractBuildNumber(title);
            BuildStatusEnum status = RssUtil.extractStatus(title);
            Element linkElement = element.getChild(RSS_LINK, rootElement.getNamespace());
            String link = linkElement.getAttributeValue(RSS_LINK_HREF);

            if (!BuildStatusEnum.NULL.equals(status)) {
                buildMap.put(jobName, Build.createBuildFromRss(link, number, status.getStatus(), Boolean.FALSE.toString(), publishedBuild, title));

            }

        }

        return buildMap;
    }

    public List<Job> loadFavoriteJobs(List<JenkinsConfiguration.FavoriteJob> favoriteJobs) {
        List<Job> jobs = new LinkedList<Job>();
        for (JenkinsConfiguration.FavoriteJob favoriteJob : favoriteJobs) {
            jobs.add(loadJob(favoriteJob.url));
        }
        return jobs;
    }

    interface LoadViewStrategy {
        public List<Job> loadJenkinsView(Document document);
    }

    private static class LoadClassicViewStrategy implements LoadViewStrategy {
        public List<Job> loadJenkinsView(Document document) {
            List<Element> jobElements = document.getRootElement().getChildren(JOB);
            List<Job> jobs = new LinkedList<Job>();
            for (Element jobElement : jobElements) {
                jobs.add(createJob(jobElement));
            }
            return jobs;
        }
    }

    private static class LoadCloudbeesViewStrategy implements LoadViewStrategy {
        public List<Job> loadJenkinsView(Document document) {
            List<Element> viewElements = document.getRootElement().getChildren(VIEW);
            if (viewElements.isEmpty()) {
                return Collections.emptyList();
            }
//TODO remove duplication with an Abstract class
            Element viewElement = viewElements.get(0);
            List<Element> jobElements = viewElement.getChildren(JOB);
            List<Job> jobs = new LinkedList<Job>();
            for (Element jobElement : jobElements) {
                jobs.add(createJob(jobElement));
            }
            return jobs;
        }
    }
}

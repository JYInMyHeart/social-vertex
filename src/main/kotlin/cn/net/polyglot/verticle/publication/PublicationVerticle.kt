package cn.net.polyglot.verticle.publication

import cn.net.polyglot.config.*
import cn.net.polyglot.module.lastHour
import cn.net.polyglot.module.nextHour
import com.codahale.fastuuid.UUIDGenerator
import io.netty.util.internal.StringUtil
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.core.file.*
import io.vertx.kotlin.core.json.jsonArrayOf
import io.vertx.kotlin.core.json.jsonObjectOf
import io.vertx.kotlin.coroutines.CoroutineVerticle
import kotlinx.coroutines.launch
import java.io.File.separator
import java.security.SecureRandom
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.*

class PublicationVerticle : CoroutineVerticle() {
  private val generator = UUIDGenerator(SecureRandom())
  private val transferedSeparator = "#"

  override suspend fun start() {
    vertx.eventBus().consumer<JsonObject>(this::class.java.name) {
      launch { it.reply(article(it.body())) }
    }
  }

  private suspend fun article(json: JsonObject): JsonObject {
    return try {
      when (json.getString(SUBTYPE)) {
        QUESTION, ARTICLE, IDEA, THOUGHT, ANSWER -> post(json)
        HISTORY -> history(json)
        UPDATE -> update(json)
        RETRIEVE -> retrieve(json)
        REPLY -> reply(json)
        COMMENT -> comment(json)
        COMMENT_LIST -> commentList(json)
        LIKE -> like(json)
        DISLIKE -> dislike(json)
        COLLECT -> collect(json)
        COLLECT_LIST -> collectList(json)
        else -> json.put(PUBLICATION, false)
      }
    } catch (e: Exception) {
      e.printStackTrace()
      json.put(PUBLICATION, false).put(INFO, e.message)
    }
  }


  //收藏
  private suspend fun collect(json: JsonObject): JsonObject {
    //get the brief or publicationo of the article
    if(!json.containsKey(DIR)) return json.put(PUBLICATION,false).put(INFO,"Directory is required")
    val dir = json.getString(DIR)
    val communityArticlePath = "${config.getString(DIR)}$separator$COMMUNITY$dir"
    val articleBrief:JsonObject
    val fs = vertx.fileSystem()
    articleBrief = when {
      fs.existsAwait("$communityArticlePath$separator${BRIEF}.json") -> fs.readFileAwait("$communityArticlePath$separator${BRIEF}.json").toJsonObject()
      fs.existsAwait("$communityArticlePath$separator${PUBLICATION}.json") -> fs.readFileAwait("$communityArticlePath$separator${PUBLICATION}.json").toJsonObject()
      else -> return json.put(PUBLICATION,false).put(INFO,"Article $dir dose not exists!")
    }

    //create a .collect/ dir at the root dir for the user and create empty file to index the article
    val userDir = "${config.getString(DIR)}$separator${json.getString(ID)}"
    val userCollectDir = "$userDir$separator$DOT_COLLECT"
    if (!fs.existsAwait(userCollectDir)) {
      fs.mkdirAwait(userCollectDir)
    }
    val collectedArticles = fs.readDirAwait(userCollectDir)
    val transferedDir = dir.replace(separator,transferedSeparator)
    if (collectedArticles.contains("$userCollectDir$separator$transferedDir")){
//      //uncollect case
      fs.deleteAwait("$userCollectDir$separator$transferedDir")
    }else{
//      //collect case
      articleBrief.put(COLLECTED_TIME,System.currentTimeMillis())
      fs.createFileAwait("$userCollectDir$separator$transferedDir")
      fs.writeFileAwait("$userCollectDir$separator$transferedDir",articleBrief.toBuffer())
    }

    //create a collect.json at the article's dir , aiming to store the userIds/num of collection
    val collectFilePath = "$communityArticlePath$separator${COLLECT}.json"
    if (!fs.existsAwait(collectFilePath)){
      fs.createFileAwait(collectFilePath)
      val initialContent = jsonObjectOf().put(COUNT,0).put(IDS, JsonArray())
      fs.writeFileAwait(collectFilePath,initialContent.toBuffer())
    }
    val collectInfo = fs.readFileAwait(collectFilePath).toJsonObject()
    val count = collectInfo.getInteger(COUNT)
    val ids:JsonArray = collectInfo.getJsonArray(IDS)
    if (ids.contains(json.getString(ID))){
      //if a user has never collected the article,add the count and ids
      collectInfo.put(COUNT,count-1)
      ids.remove(json.getString(ID))
      collectInfo.put(IDS,ids)
    }else {
      //if the user already collected the artcle, this case means that uncollect
      collectInfo.put(COUNT, count + 1)
      collectInfo.put(IDS, ids.add(json.getString(ID)))
    }
    fs.writeFileAwait(collectFilePath,collectInfo.toBuffer())

    return jsonObjectOf().put(PUBLICATION,true).put(TYPE, PUBLICATION).put(SUBTYPE, COLLECT)
  }


  //get a list of a user's collected articles. todo UT
  private suspend fun collectList(json: JsonObject): JsonObject {
    val fs = vertx.fileSystem()
    //current userId
    val id = json.getString(ID)
    val collectPath = "${config.getString(DIR)}$separator$id$separator$DOT_COLLECT"
    val collectedArticles = fs.readDirAwait(collectPath)
    val articles = collectedArticles
      .map {
        val simplifiedDir = it.substringAfterLast(separator).replace(transferedSeparator, separator)
        fs.readFileAwait(it).toJsonObject().put(DIR,simplifiedDir)
      }
      .sortedBy { it.getLong(COLLECTED_TIME) }.reversed()
    //todo pageable list
    // get pageable params and return one page
    return jsonObjectOf().put(SUBTYPE, COLLECT_LIST).put(PUBLICATION,true).put(INFO,articles)
  }


  //踩
  private suspend fun dislike(json: JsonObject): JsonObject {
    val fs = vertx.fileSystem()
    if(!json.containsKey(DIR)) return json.put(PUBLICATION,false).put(INFO,"Directory is required")
    val dir = json.getString(DIR)
    val communityArticlePath = "${config.getString(DIR)}$separator$COMMUNITY$separator$dir"
    //handle community dislike info
    if (!fs.existsAwait("$communityArticlePath$separator${DISLIKE}.json")){
      fs.createFileAwait("$communityArticlePath$separator${DISLIKE}.json")
      val initialDislike = jsonObjectOf().put(COUNT,0).put(IDS, JsonArray())
      fs.writeFileAwait("$communityArticlePath$separator${DISLIKE}.json",initialDislike.toBuffer())
    }
    val articleDislikeInfo = fs.readFileAwait("$communityArticlePath$separator${DISLIKE}.json").toJsonObject()
    val ids = articleDislikeInfo.getJsonArray(IDS)
    val count = articleDislikeInfo.getInteger(COUNT)
    if (articleDislikeInfo.getJsonArray(IDS).contains(json.getString(ID))){
      ids.remove(json.getString(ID))
      articleDislikeInfo.put(IDS,ids)
      articleDislikeInfo.put(COUNT,count-1)
    }else{
      ids.add(json.getString(ID))
      articleDislikeInfo.put(IDS,ids)
      articleDislikeInfo.put(COUNT,count+1)
    }
    fs.writeFileAwait("$communityArticlePath$separator${DISLIKE}.json",articleDislikeInfo.toBuffer())

    //-----------------------------------------------------------
    //handle user dislike info
    val userDir = "${config.getString(DIR)}$separator${json.getString(ID)}"
    val userDislikeJsonDir = "$userDir$separator${DISLIKE}.json"
    if(!fs.existsAwait(userDislikeJsonDir)) {
      fs.createFileAwait(userDislikeJsonDir)
      fs.writeFileAwait(userDislikeJsonDir, JsonArray().toBuffer())
    }
    val dislikedArticles = fs.readFileAwait(userDislikeJsonDir).toJsonArray()
    when{
      dislikedArticles.contains(dir) -> {
        // if already disliked,cancle it
        dislikedArticles.remove(dir)
        fs.writeFileAwait(userDislikeJsonDir,dislikedArticles.toBuffer())
      }
      else->{
        //if not disliked, then dislike it
        dislikedArticles.add(dir)
        fs.writeFileAwait(userDislikeJsonDir,dislikedArticles.toBuffer())
      }
    }
    //-------------
    return jsonObjectOf().put(SUBTYPE, DISLIKE).put(PUBLICATION,true)
  }

  //点赞
  private suspend fun like(json: JsonObject): JsonObject {
    val fs = vertx.fileSystem()
    if(!json.containsKey(DIR)) return json.put(PUBLICATION,false).put(INFO,"Directory is required")
    val dir = json.getString(DIR)
    val communityArticlePath = "${config.getString(DIR)}$separator$COMMUNITY$separator$dir"
    //handle community dislike info
    if (!fs.existsAwait("$communityArticlePath$separator${LIKE}.json")){
      fs.createFileAwait("$communityArticlePath$separator${LIKE}.json")
      val initialDislike = jsonObjectOf().put(COUNT,0).put(IDS, JsonArray())
      fs.writeFileAwait("$communityArticlePath$separator${LIKE}.json",initialDislike.toBuffer())
    }
    val articleDislikeInfo = fs.readFileAwait("$communityArticlePath$separator${LIKE}.json").toJsonObject()
    val ids = articleDislikeInfo.getJsonArray(IDS)
    val count = articleDislikeInfo.getInteger(COUNT)
    if (articleDislikeInfo.getJsonArray(IDS).contains(json.getString(ID))){
      ids.remove(json.getString(ID))
      articleDislikeInfo.put(IDS,ids)
      articleDislikeInfo.put(COUNT,count-1)
    }else{
      ids.add(json.getString(ID))
      articleDislikeInfo.put(IDS,ids)
      articleDislikeInfo.put(COUNT,count+1)
    }
    fs.writeFileAwait("$communityArticlePath$separator${LIKE}.json",articleDislikeInfo.toBuffer())

    //-----------------------------------------------------------
    //handle user dislike info
    val userDir = "${config.getString(DIR)}$separator${json.getString(ID)}"
    val userDislikeJsonDir = "$userDir$separator${LIKE}.json"
    if(!fs.existsAwait(userDislikeJsonDir)) {
      fs.createFileAwait(userDislikeJsonDir)
      fs.writeFileAwait(userDislikeJsonDir, JsonArray().toBuffer())
    }
    val dislikedArticles = fs.readFileAwait(userDislikeJsonDir).toJsonArray()
    when{
      dislikedArticles.contains(dir) -> {
        // if already disliked,cancle it
        dislikedArticles.remove(dir)
        fs.writeFileAwait(userDislikeJsonDir,dislikedArticles.toBuffer())
      }
      else->{
        //if not liked, then like it
        dislikedArticles.add(dir)
        fs.writeFileAwait(userDislikeJsonDir,dislikedArticles.toBuffer())
      }
    }
    //-------------
    return jsonObjectOf().put(SUBTYPE, LIKE).put(PUBLICATION,true)
  }

  //获取评论列表
  private suspend fun commentList(json: JsonObject): JsonObject {
    val dir = json.getString(DIR)
    val atcPath = "${config.getString(DIR)}$separator$COMMUNITY$dir"
    val commentsPath = "$atcPath$separator$COMMENTS"
    if (StringUtil.isNullOrEmpty(dir)){
      return json.put(PUBLICATION,false).put(INFO,"Dir field is required!")
    }
    val fs = vertx.fileSystem()
    if (!fs.existsAwait(atcPath)){
      json.put(PUBLICATION,false).put(INFO,"no such dir: $dir")
    }
    if (!fs.existsAwait(commentsPath)){
      return json.put(PUBLICATION,true).put(INFO, JsonArray())
    }
    val comments = fs.readDirAwait(commentsPath)
    val commentsList = comments.map { fs.readFileAwait("$it$separator${PUBLICATION}.json").toJsonObject() }
      .sortedBy { "${it.getString(DATE)}${it.getString(TIME)}" }
    return json.put(INFO,commentsList).put(PUBLICATION,true)
  }

  //评论
  private suspend fun comment(json: JsonObject): JsonObject {
    /* ********************************************************************************************
     #  interface args structure:
     #{
     #  "type":"publication",
     #  "subtype":"comment",
     #  "dir":"/2019/06/29/15/387a71fc-f440-47ab-9d4a-bdbc7cbff5dd",被评论的 "文章/评论" 的路径
     #  "content":"str.....this is a comment for an article or a comment",评论内容
     #  "id":"zxj2019",                                             用户名
     #  "password":"431fe828b9b8e8094235dee515562247"               密码
     #}
     # # # # # # # # # #
     # directory structure example
     #  uuid(dir)
     #    |--publication.json
     #    |--comments
     #         |--uuid1(dir)
     #              |--comments
     #              |--publication.json
     #         |--uuid2(dir)
     #              |--comments
     #              |--publication.json
     # # # # # # # # # #
     # comment 实体：
     # {
     #   "dir":"/2019/06/29/15/387a71fc-f440-47ab-9d4a-bdbc7cbff5dd"
     #   "content":""
     #   "id":"xxx" //评论者
     #   "commented_user_id":"xxx2"//被评论者
     #   "atted_user_id":"xxx3"//用户展示comments of a comment时 被评论者的id
     # }
     *************************************************************************************************
    */
    //--------------------------------------------------
    //check the dir and comment
    val commentContent = json.getString(CONTENT)
    if(StringUtil.isNullOrEmpty(commentContent)) {
      return json.put(PUBLICATION,false).put(INFO,"The comment can not be null or empty!")
    }
    val dir = json.getString(DIR)
    //this is a path of an article or a path of comment going to be commented
    val articlePath = "${config.getString(DIR)}$separator$COMMUNITY$dir"
    //create comments dir
    val commentsPath = "$articlePath$separator$COMMENTS"

    val fs = vertx.fileSystem()
    if (!fs.existsAwait(articlePath)){
      return json.put(PUBLICATION,false).put(INFO,"no such dir: $dir")
    }

    if (!fs.existsAwait(commentsPath)){
      //if comments dir dose not exists create one
      fs.mkdirAwait(commentsPath)
    }

    val uuid = generator.generate().toString()
    val newCommentPath = "$commentsPath$separator$uuid"
    fs.mkdirAwait(newCommentPath)
    val commentFilePath = "$newCommentPath$separator${PUBLICATION}.json"
    fs.createFileAwait(commentFilePath)
    //-----------------------------------------------------------
    //commented user(被回复的用户id)
    val commentedUserId = fs.readFileAwait("$articlePath$separator${PUBLICATION}.json").toJsonObject().getString(ID)
    json.put(COMMENTED_USER_ID,commentedUserId)
    //remove the unnecessary password field
    json.remove(PASSWORD)
    json.put(DIR,"$dir$separator$COMMENTS$separator$uuid")
    //fill in date and time
    val date = Date()
    val today = SimpleDateFormat("yyyy-MM-dd").format(date)
    val time = SimpleDateFormat("hh:mm:ss").format(date)
    json.put(DATE, today)
    json.put(TIME, time)
    //---------
    fs.writeFileAwait(commentFilePath,json.toBuffer())
    return jsonObjectOf().put(SUBTYPE, COMMENT).put(PUBLICATION,true)
  }

  //todo 需完善以及unit tests
  private suspend fun reply(json:JsonObject):JsonObject{
    return try{
      val dir = json.getString(DIR)

      val dirPath = "${config.getString(DIR)}$separator$COMMUNITY$separator$dir"

      if(!vertx.fileSystem().existsAwait(dirPath)){
        return jsonObjectOf().put(PUBLICATION, false).put(INFO, "$dirPath doesn't exist")
      }

      json.put(TIME_ORDER_STRING, "${System.currentTimeMillis()}")
      json.put(DEFAULT_ORDER_STRING, "${System.currentTimeMillis()}")

      val file = generator.generate().toString()

      vertx.fileSystem().writeFileAwait("$dirPath$separator$file.reply.json", json.toBuffer())

      json
    }catch (e:Throwable){
      jsonObjectOf().put(PUBLICATION, false).put(INFO, e.message)
    }

  }

  private suspend fun post(json: JsonObject): JsonObject {

    val fs = vertx.fileSystem()

    val date = Date()
    val today = SimpleDateFormat("yyyy-MM-dd").format(date)
    val time = SimpleDateFormat("hh:mm:ss").format(date)

    json.put(DATE, today)
    json.put(TIME, time)

    val yyyy = SimpleDateFormat("yyyy").format(date)
    val mm = SimpleDateFormat("MM").format(date)
    val dd = SimpleDateFormat("dd").format(date)
    val hh = SimpleDateFormat("HH").format(date)

    val dirName = generator.generate().toString()

    val communityPath = "${config.getString(DIR)}$separator$COMMUNITY$separator$yyyy$separator$mm$separator$dd$separator$hh$separator$dirName"

    fs.mkdirsAwait(communityPath)
    fs.writeFileAwait("$communityPath${separator}publication.json", json.toBuffer())

    val briefJson = json.copy()
    if(briefJson.containsKey(CONTENT) && briefJson.getValue(CONTENT) !=null &&
      briefJson.getValue(CONTENT) is String && briefJson.getString(CONTENT).length>100){
      val briefContent = briefJson.getString(CONTENT).substring(0,100).plus("...")
      briefJson.put(CONTENT, briefContent)
      fs.writeFileAwait("$communityPath${separator}brief.json", briefJson.toBuffer())
    }

    val linkPath = "${config.getString(DIR)}$separator${json.getString(ID)}$separator$COMMUNITY$separator$yyyy$separator$mm$separator$dd$separator$hh"
    fs.mkdirsAwait(linkPath)
    fs.createFileAwait("$linkPath$separator$dirName")

    return jsonObjectOf().put(PUBLICATION, true).put(DIR, "$separator$yyyy$separator$mm$separator$dd$separator$hh$separator$dirName")
  }

  private suspend fun retrieve(json: JsonObject): JsonObject {
    if (!json.containsKey(DIR)) return json.put(PUBLICATION, false).put(INFO, "Directory is required")

    val path = "${config.getString(DIR)}$separator$COMMUNITY${json.getString(DIR)}$separator" + "publication.json"

    return try {
      //--- fill in like/dislike/collect info ---
      val file = vertx.fileSystem().readFileAwait(path).toJsonObject()
      file.put(DIR,json.getString(DIR))
      handleRelatedInfo(file)
      file.put(PUBLICATION, true)
    } catch (e: Throwable) {
      e.printStackTrace()
      json.put(PUBLICATION, false).put(INFO, e.message)
    }
  }

  //update article
  private suspend fun update(json: JsonObject): JsonObject {
    //check arg of DIR
    if(!json.containsKey(DIR)) return json.put(PUBLICATION,false).put(INFO,"Directory is required")
    val dir = "${config.getString(DIR)}$separator$COMMUNITY${json.getString(DIR)}"
    val originalPath = "$dir${separator}publication.json"
    val newPath = "$dir${separator}publication_new.json"
    try {
      //set the subtype
      val originalArticle = vertx.fileSystem().readFileAwait(originalPath).toJsonObject()
      json.put(SUBTYPE,originalArticle.getString(SUBTYPE))

      //handle brief
      val briefJson = json.copy()
      if(briefJson.containsKey(CONTENT) && briefJson.getValue(CONTENT) !=null &&
        briefJson.getValue(CONTENT) is String && briefJson.getString(CONTENT).length>100){
        val briefContent = briefJson.getString(CONTENT).substring(0,100).plus("...")
        briefJson.put(CONTENT, briefContent)
        vertx.fileSystem().writeFileAwait("$dir${separator}brief.json", briefJson.toBuffer())
      }

      //create publication_new.json
      vertx.fileSystem().writeFileAwait(newPath, json.toBuffer())

      //remove the older file
      vertx.fileSystem().deleteAwait(originalPath)

      //rename new file to publication.json
      vertx.fileSystem().moveAwait(newPath, originalPath)
    }catch (e: Throwable){
      e.printStackTrace()
      json.put(PUBLICATION,false).put(INFO,e.message)
    }
    return jsonObjectOf().put(TYPE,json.getString(TYPE)).put(SUBTYPE,json.getString(SUBTYPE)).put(PUBLICATION,true)
  }

  private suspend fun history(json: JsonObject): JsonObject {

    if (json.getString(TIME) == null) {
      val nextHour = SimpleDateFormat("yyyy-MM-dd-HH").format(Date().nextHour())
      json.put(TIME, nextHour)
    } else try {
      SimpleDateFormat("yyyy-MM-dd-HH").parse(json.getString(TIME))
    } catch (e: ParseException) {
      json.remove(TIME)
      val nextHour = SimpleDateFormat("yyyy-MM-dd-HH").format(Date().nextHour())
      json.put(TIME, nextHour)
    }

    val time = SimpleDateFormat("yyyy-MM-dd-HH").parse(json.getString(TIME)).lastHour()

    val yyyy = SimpleDateFormat("yyyy").format(time)
    val mm = SimpleDateFormat("MM").format(time)
    val dd = SimpleDateFormat("dd").format(time)
    val hh = SimpleDateFormat("HH").format(time)

    val dir = if (json.containsKey(FROM)) {
      if (!vertx.fileSystem().existsAwait("${config.getString(DIR)}$separator${json.getString(FROM)}")) {
        return json.put(PUBLICATION, false).put(INFO, "User doesn't exist")
      }
      "${config.getString(DIR)}$separator${json.getString(FROM)}$separator$COMMUNITY"
    } else {
      "${config.getString(DIR)}$separator$COMMUNITY"
    }
    if (!vertx.fileSystem().existsAwait(dir)) {
      vertx.fileSystem().mkdirsAwait(dir)
    }

    val history = jsonArrayOf()
    var until = "$yyyy-$mm-$dd-$hh"

    val yyyys = vertx.fileSystem()
      .readDirAwait(dir, "\\d{4}")
      .map { it.substringAfterLast(separator) }
      .filter { it <= yyyy }
      .sortedDescending()

    loop@ for (year in yyyys) {
      val mms = vertx.fileSystem()
        .readDirAwait("$dir$separator$year", "\\d{2}")
        .map { it.substringAfterLast(separator) }
        .filter { year + it <= yyyy + mm }
        .sortedDescending()

      for (month in mms) {
        val dds = vertx.fileSystem()
          .readDirAwait("$dir$separator$year$separator$month", "\\d{2}")
          .map { it.substringAfterLast(separator) }
          .filter { year + month + it <= yyyy + mm + dd }
          .sortedDescending()

        for (day in dds) {
          val hhs = vertx.fileSystem()
            .readDirAwait("$dir$separator$year$separator$month$separator$day", "\\d{2}")
            .map { it.substringAfterLast(separator) }
            .filter { year + month + day + it <= yyyy + mm + dd + hh }
            .sortedDescending()

          for (hour in hhs) {
            val publicationList = vertx.fileSystem()
              .readDirAwait("$dir$separator$year$separator$month$separator$day$separator$hour")

            for (publicationPath in publicationList) {
              val props = vertx.fileSystem().propsAwait(publicationPath)
              val publicationFilePath = if (props.isDirectory) {
                "$publicationPath$separator" + "publication.json"
              } else {
                "${config.getString(DIR)}$separator$COMMUNITY$separator$year$separator$month$separator$day$separator$hour$separator${publicationPath.substringAfterLast(separator)}$separator" + "publication.json"
              }
              val briefFilePath = publicationFilePath.replace("publication.json","brief.json")
              val filePath = if(vertx.fileSystem().existsAwait(briefFilePath))
                briefFilePath
              else
                publicationFilePath

              until = "$year-$month-$day-$hour"

              val file = try {
                vertx.fileSystem().readFileAwait(filePath).toJsonObject()
              }catch (e:Throwable){
                jsonObjectOf(Pair(INFO, e.message))
              }.put(DIR, publicationFilePath.substringAfterLast(COMMUNITY).substringBeforeLast(separator))

              //--- fill in like/dislike/collect info ---
              handleRelatedInfo(file)
              //------------------------------------------------
              history.add(file)
            }

            if (history.size() >= 20) break@loop
          }
        }
      }
    }

    return json.put(PUBLICATION, true).put(HISTORY, history).put(TIME, until)
  }

  /**
   * this method handles likes/dislikes/collect number for an article
   */
  private suspend fun handleRelatedInfo(file: JsonObject) {
    val basePath = "${config.getString(DIR)}$separator$COMMUNITY$separator${file.getString(DIR)}"
    if (!vertx.fileSystem().existsAwait("$basePath$separator${LIKE}.json")) {
      //No like.json case means no one has liked before
      vertx.fileSystem().createFileAwait("$basePath$separator${LIKE}.json")
      vertx.fileSystem().writeFileAwait("$basePath$separator${LIKE}.json", jsonObjectOf().put(COUNT,0).put(IDS, jsonArrayOf()).toBuffer())
      file.put(LIKE, 0)
    } else {
      val like = vertx.fileSystem().readFileAwait("$basePath$separator${LIKE}.json").toJsonObject()
      file.put(LIKE, like.getInteger(COUNT))
    }
    if (!vertx.fileSystem().existsAwait("$basePath$separator${DISLIKE}.json")) {
      vertx.fileSystem().writeFileAwait("$basePath$separator${DISLIKE}.json", jsonObjectOf().put(COUNT,0).put(IDS, jsonArrayOf()).toBuffer())
      file.put(DISLIKE, 0)
    } else {
      val dislike = vertx.fileSystem().readFileAwait("$basePath$separator${DISLIKE}.json").toJsonObject()
      file.put(DISLIKE, dislike.getInteger(COUNT))
    }
    if (!vertx.fileSystem().existsAwait("$basePath$separator${COLLECT}.json")) {
      vertx.fileSystem().writeFileAwait("$basePath$separator${COLLECT}.json", jsonObjectOf().put(COUNT,0).put(IDS, jsonArrayOf()).toBuffer())
      file.put(COLLECT, 0)
    } else {
      val dislike = vertx.fileSystem().readFileAwait("$basePath$separator${COLLECT}.json").toJsonObject()
      file.put(COLLECT, dislike.getInteger(COUNT))
    }
  }
}

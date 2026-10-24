package cc.hyoban.moments.ui.format

import cc.hyoban.moments.R
import cc.hyoban.moments.data.spec.MomentTopic

/** What a topic is called on screen. */
fun topicLabel(topic: MomentTopic) = when (topic) {
    MomentTopic.TECH -> R.string.topic_tech
    MomentTopic.TRAVEL -> R.string.topic_travel
    MomentTopic.FOOD -> R.string.topic_food
    MomentTopic.CAR -> R.string.topic_car
    MomentTopic.PHOTO -> R.string.topic_photo
    MomentTopic.LIFE -> R.string.topic_life
}
